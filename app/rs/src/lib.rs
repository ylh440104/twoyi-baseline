// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

use jni::objects::{GlobalRef, JValue};
use jni::sys::{jclass, jfloat, jint, jobject, JNI_ERR, jstring};
use jni::JNIEnv;
use jni::{JavaVM, NativeMethod};
use log::{error, info, Level, debug};
use ndk_sys;
use once_cell::sync::OnceCell;
use std::ffi::c_void;

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::os::unix::process::CommandExt;
use std::time::Duration;
use std::thread;

use android_logger::Config;

use std::fs::OpenOptions;
use std::os::unix::fs::OpenOptionsExt;
use std::process::{Command, Stdio};

mod input;
mod input_wire;
mod event_stream;
mod touch_slots;
mod touch_geometry;
mod key_events;
mod renderer_bindings;

/// ## Examples
/// ```
/// let method:NativeMethod = jni_method!(native_method, "(Ljava/lang/String;)V");
/// ```
macro_rules! jni_method {
    ( $name: tt, $method:tt, $signature:expr ) => {{
        jni::NativeMethod {
            name: jni::strings::JNIString::from(stringify!($name)),
            sig: jni::strings::JNIString::from($signature),
            fn_ptr: $method as *mut c_void,
        }
    }};
}

#[derive(PartialEq, Eq)]
struct GuestPaths {
    loader: String,
    root: String,
    log: String,
    touch: String,
    key: String,
}

// The legacy renderer and device servers are process singletons. A replacement
// Surface must belong to the same guest that started them.
static RENDERER_GUEST: once_cell::sync::Lazy<Mutex<Option<GuestPaths>>> =
    once_cell::sync::Lazy::new(|| Mutex::new(None));
static GUEST_START_FAILED: AtomicBool = AtomicBool::new(false);
static JVM_HANDLE: OnceCell<JavaVM> = OnceCell::new();
static RENDERER_CLASS: OnceCell<GlobalRef> = OnceCell::new();
static GUEST_CHILD: once_cell::sync::Lazy<Mutex<Option<std::process::Child>>> =
    once_cell::sync::Lazy::new(|| Mutex::new(None));
static GUEST_STOPPING: AtomicBool = AtomicBool::new(false);

// ndk 0.6 NativeWindow is a pointer wrapper. It does not release references.
struct OwnedWindow(ndk::native_window::NativeWindow);

impl OwnedWindow {
    fn from_surface(env: &JNIEnv, surface: jobject) -> Option<Self> {
        let ptr = unsafe { ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), surface) };
        std::ptr::NonNull::new(ptr).map(|ptr| Self(unsafe {
            ndk::native_window::NativeWindow::from_ptr(ptr)
        }))
    }

    fn raw(&self) -> *mut ndk_sys::ANativeWindow {
        self.0.ptr().as_ptr()
    }

    fn acquire(&self) -> Self {
        unsafe { ndk_sys::ANativeWindow_acquire(self.raw()) };
        Self(self.0.clone())
    }
}

impl Drop for OwnedWindow {
    fn drop(&mut self) {
        unsafe { ndk_sys::ANativeWindow_release(self.raw()) };
    }
}

static ACTIVE_WINDOW: once_cell::sync::Lazy<Mutex<Option<OwnedWindow>>> =
    once_cell::sync::Lazy::new(|| Mutex::new(None));

fn resize_window(window: &OwnedWindow, size: (i32, i32)) {
    let (width, height) = size;
    unsafe {
        renderer_bindings::resetSubWindow(
            window.raw().cast(), 0, 0, width, height, width, height, 1.0, 0.0,
        );
    }
}

extern "system" fn guest_init_pid(_env: JNIEnv, _clz: jclass) -> jint {
    GUEST_CHILD.lock().unwrap().as_ref().map_or(0, |child| child.id() as jint)
}

extern "system" fn stop_guest(_env: JNIEnv, _clz: jclass) -> jint {
    let mut slot = GUEST_CHILD.lock().unwrap();
    GUEST_STOPPING.store(true, Ordering::Release);
    if let Some(child) = slot.as_mut() {
        // The unreaped child is the leader of a group created by this host.
        // Hold the lock so its PID cannot be reaped and reused before signaling.
        let group = -(child.id() as libc::pid_t);
        if unsafe { libc::kill(group, libc::SIGKILL) } != 0 {
            let error = std::io::Error::last_os_error();
            if error.raw_os_error() != Some(libc::ESRCH) {
                error!("Could not stop guest process group: {}", error);
                return 0;
            }
            // A guest that changed groups may still have a live init process.
            if let Err(error) = child.kill() {
                if error.raw_os_error() != Some(libc::ESRCH) {
                    error!("Could not stop guest init: {}", error);
                    return 0;
                }
            }
        }
    }
    for _ in 0..40 {
        match slot.as_mut().map(|child| child.try_wait()) {
            None | Some(Ok(Some(_))) => {
                *slot = None;
                return 1;
            }
            Some(Ok(None)) => thread::sleep(Duration::from_millis(50)),
            Some(Err(e)) => {
                error!("Could not reap guest init: {}", e);
                return 0;
            }
        }
    }
    error!("Timed out waiting for guest init to stop");
    0
}

fn monitor_guest() {
    loop {
        let exit = {
            let mut slot = GUEST_CHILD.lock().unwrap();
            let child = match slot.as_mut() {
                Some(child) => child,
                None => return,
            };
            match child.try_wait() {
                Ok(Some(status)) => {
                    // Publish the terminal state before releasing the child slot.
                    GUEST_START_FAILED.store(true, Ordering::Release);
                    *slot = None;
                    Some(status.code().unwrap_or(-1))
                }
                Ok(None) => None,
                Err(e) => {
                    error!("Cannot check guest init status: {}", e);
                    return;
                }
            }
        };
        if let Some(code) = exit {
            notify_guest_exited(code);
            return;
        }
        thread::sleep(Duration::from_millis(250));
    }
}

fn notify_guest_exited(exit_code: i32) {
    GUEST_START_FAILED.store(true, Ordering::Release);
    if let (Some(jvm), Some(clazz)) = (JVM_HANDLE.get(), RENDERER_CLASS.get()) {
        if let Ok(env) = jvm.attach_current_thread() {
            let class_obj: jni::objects::JClass = clazz.as_obj().into();
            let _ = env.call_static_method(
                class_obj,
                "onGuestExited",
                "(I)V",
                &[JValue::Int(exit_code)],
            );
            let _ = env.exception_clear();
        }
    }
}

pub extern "system" fn guest_failed(_env: JNIEnv, _clz: jclass) -> jint {
    if GUEST_START_FAILED.load(Ordering::Acquire) { 1 } else { 0 }
}

#[no_mangle]
pub extern "system" fn renderer_init(
    env: JNIEnv,
    _clz: jclass,
    surface: jobject,
    loader: jstring,
    root: jstring,
    log: jstring,
    touch: jstring,
    key: jstring,
    xdpi: jfloat,
    ydpi: jfloat,
    fps: jint,
) {
    debug!("renderer_init");
    if GUEST_START_FAILED.load(Ordering::Acquire) || GUEST_STOPPING.load(Ordering::Acquire) {
        let _ = env.throw_new("java/lang/IllegalStateException",
            "Guest startup previously failed; restart the host process before retrying");
        return;
    }
    let window = match OwnedWindow::from_surface(&env, surface) {
        Some(x) => x,
        None => {
            error!("ANativeWindow_fromSurface was null!");
            let _ = env.throw_new("java/lang/IllegalStateException", "No valid render Surface");
            return;
        }
    };

    let width = window.0.width();
    let height = window.0.height();
    if width <= 0 || height <= 0 {
        let _ = env.throw_new("java/lang/IllegalStateException", "Invalid guest window dimensions");
        return;
    }

    info!(
        "renderer_init width: {}, height: {}, fps: {}",
        width, height, fps
    );

    let read_path = |value: jstring| env.get_string(value.into()).map(String::from);
    let paths = (read_path(loader), read_path(root), read_path(log), read_path(touch), read_path(key));
    let paths = match paths {
        (Ok(loader), Ok(root), Ok(log), Ok(touch), Ok(key)) => GuestPaths { loader, root, log, touch, key },
        _ => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", "Invalid guest endpoint paths");
            return;
        }
    };
    let mut renderer_guest = RENDERER_GUEST.lock().unwrap();
    let mut child_slot = GUEST_CHILD.lock().unwrap();
    if GUEST_STOPPING.load(Ordering::Acquire) || GUEST_START_FAILED.load(Ordering::Acquire) {
        let _ = env.throw_new("java/lang/IllegalStateException", "Guest session is no longer available");
        return;
    }
    if let Some(active_guest) = renderer_guest.as_ref() {
        if active_guest != &paths {
            let _ = env.throw_new("java/lang/IllegalStateException",
                "The renderer is bound to a different guest in this host process");
            return;
        }
        input::cancel_touch();
        let mut active = ACTIVE_WINDOW.lock().unwrap();
        let win = window.raw() as *mut c_void;
        unsafe { renderer_bindings::setNativeWindow(win) };
        resize_window(&window, (width, height));
        *active = Some(window);
    } else {
        // Resolve init before starting process-lifetime renderer and input resources.
        let root = std::path::Path::new(&paths.root);
        let executable = root.join("init");
        let regular_init = executable.symlink_metadata().map_or(false, |entry| entry.is_file());
        if !root.is_absolute() || !regular_init {
            let _ = env.throw_new("java/lang/IllegalStateException", "Guest init is missing from the configured ROM");
            return;
        }
        let logs = OpenOptions::new()
            .write(true)
            .create(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW | libc::O_NONBLOCK)
            .open(&paths.log)
            .and_then(|output| {
                if !output.metadata()?.is_file() {
                    return Err(std::io::Error::new(std::io::ErrorKind::InvalidInput,
                        "Guest log path is not a regular file"));
                }
                output.set_len(0)?;
                let errors = output.try_clone()?;
                Ok((output, errors))
            });
        let (outputs, errors) = match logs {
            Ok(logs) => logs,
            Err(error) => {
                let message = format!("Cannot prepare guest log: {}", error);
                error!("{}", message);
                let _ = env.throw_new("java/lang/IllegalStateException", message);
                return;
            }
        };
        if let Err(error) = input::start_input_system(width, height, paths.touch.clone(), paths.key.clone()) {
            let message = format!("Cannot initialize guest input sockets: {}", error);
            error!("{}", message);
            let _ = env.throw_new("java/lang/IllegalStateException", message);
            return;
        }

        // From here on, the input servers and renderer cannot be reset in-process.
        // Keep their endpoint identity even if launching init fails.
        let startup_window = window.acquire();
        *ACTIVE_WINDOW.lock().unwrap() = Some(window);
        thread::spawn(move || {
            let win = startup_window.raw() as *mut c_void;
            info!("win: {:#?}", win);
            unsafe {
                renderer_bindings::startOpenGLRenderer(
                    win,
                    width,
                    height,
                    xdpi as i32,
                    ydpi as i32,
                    fps as i32,
                );
            }
        });

        let launch = (|| -> Result<std::process::Child, String> {
            let mut command = Command::new(executable);
            command.current_dir(&paths.root)
                .arg0("./init")
                .env("TYLOADER", &paths.loader)
                .stdout(Stdio::from(outputs))
                .stderr(Stdio::from(errors));
            unsafe {
                command.pre_exec(|| {
                    if libc::setpgid(0, 0) == -1 {
                        return Err(std::io::Error::last_os_error());
                    }
                    Ok(())
                });
            }
            command.spawn().map_err(|e| format!("Cannot start guest init: {}", e))
        })();
        *renderer_guest = Some(paths);
        drop(renderer_guest);
        match launch {
            Ok(child) => {
                *child_slot = Some(child);
                drop(child_slot);
                thread::spawn(monitor_guest);
            }
            Err(message) => {
                GUEST_START_FAILED.store(true, Ordering::Release);
                drop(child_slot);
                notify_guest_exited(-1);
                error!("{}", message);
                let _ = env.throw_new("java/lang/IllegalStateException", message);
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn renderer_reset_window(
    env: JNIEnv,
    _clz: jclass,
    surface: jobject,
    _top: jint,
    _left: jint,
    _width: jint,
    _height: jint,
) {
    debug!("reset_window");
    if _width <= 0 || _height <= 0 {
        return;
    }
    if let Some(window) = OwnedWindow::from_surface(&env, surface) {
        let active = ACTIVE_WINDOW.lock().unwrap();
        if active.as_ref().map(OwnedWindow::raw) == Some(window.raw()) {
            resize_window(&window, (_width, _height));
        }
    }
}

#[no_mangle]
pub extern "system" fn renderer_remove_window(env: JNIEnv, _clz: jclass, surface: jobject) {
    debug!("renderer_remove_window");
    if let Some(window) = OwnedWindow::from_surface(&env, surface) {
        let mut active = ACTIVE_WINDOW.lock().unwrap();
        if active.as_ref().map(OwnedWindow::raw) == Some(window.raw()) {
            input::cancel_touch();
            unsafe { renderer_bindings::removeSubWindow(window.raw().cast()) };
            *active = None;
        }
    }
}

#[no_mangle]
pub extern "system" fn handle_touch(env: JNIEnv, _clz: jclass, event: jobject, width: jint, height: jint) {
    let pointer = match env.get_field(event, "mNativePtr", "J") {
        Ok(JValue::Long(value)) => std::ptr::NonNull::new(value as *mut ndk_sys::AInputEvent),
        Ok(_) => None,
        Err(error) => {
            debug!("Cannot read native MotionEvent: {}", error);
            if env.exception_check().unwrap_or(false) {
                let _ = env.exception_clear();
            }
            None
        }
    };
    if let Some(pointer) = pointer {
        let event = unsafe { ndk::event::MotionEvent::from_ptr(pointer) };
        input::handle_touch(event, width, height);
    } else {
        input::cancel_touch();
    }
}

extern "system" fn cancel_touch(_env: JNIEnv, _clz: jclass) {
    input::cancel_touch();
}

pub extern "system" fn send_key_code(_env: JNIEnv, _clz: jclass, keycode: jint) {
    debug!("send key code!");
    input::send_key_code(keycode);
}

/// Answer the guest image check for `path` as a one-line verdict.
///
/// This runs the very same `miniloader::preflight` the loader uses, so the app cannot
/// drift from the loader: if the app says an image is loadable, the loader agrees.
#[no_mangle]
pub extern "system" fn guest_preflight(env: JNIEnv, _clz: jclass, path: jstring) -> jstring {
    let text: String = match env.get_string(path.into()) {
        Ok(value) => String::from(value),
        Err(_) => {
            return match env.new_string("ERROR unreadable path") {
                Ok(value) => jni::objects::JObject::from(value).into_inner() as jstring,
                Err(_) => std::ptr::null_mut(),
            }
        }
    };
    let verdict = match std::fs::read(&text) {
        Ok(bytes) => {
            let mut summary = String::new();
            match miniloader::elf::Elf::parse(&bytes) {
                Ok(elf) => {
                    let mut bridge = miniloader::SymbolTable::new();
                    if let Ok(needed) = miniloader::symbols::needed_libraries(&elf) {
                        if !needed.is_empty() {
                            let opened = bridge.open_libraries(&needed);
                            if let Ok(slots) = miniloader::symbols::jump_slots(&elf) {
                                let names: Vec<String> =
                                    slots.iter().map(|slot| slot.symbol.clone()).collect();
                                let missing = bridge.resolve_names(&names);
                                summary.push_str(&format!(
                                    " libraries={}/{} unresolved={}",
                                    opened.len(),
                                    needed.len(),
                                    missing.len()
                                ));
                            }
                        }
                    }
                    let report = miniloader::preflight::preflight(&elf, &bridge, 4096);
                    format!(
                        "{} segments={} imports={} relocations={}{}",
                        if report.is_loadable() {
                            "LOADABLE"
                        } else {
                            "BLOCKED"
                        },
                        report.segments,
                        report.imports.len(),
                        report.relative_relocations,
                        summary
                    )
                }
                Err(error) => format!("NOT_ELF {error:?}"),
            }
        }
        Err(error) => format!("UNREADABLE {error}"),
    };
    match env.new_string(verdict) {
        Ok(value) => jni::objects::JObject::from(value).into_inner() as jstring,
        Err(_) => std::ptr::null_mut(),
    }
}

unsafe fn register_natives(jvm: &JavaVM, class_name: &str, methods: &[NativeMethod]) -> jint {
    let env: JNIEnv = jvm.get_env().unwrap();
    let jni_version = env.get_version().unwrap();
    let version: jint = jni_version.into();

    debug!("JNI Version : {:#?} ", jni_version);

    let clazz = match env.find_class(class_name) {
        Ok(clazz) => clazz,
        Err(e) => {
            error!("java class not found : {:?}", e);
            return JNI_ERR;
        }
    };
    debug!("clazz: {:#?}", clazz);

    if let Ok(global_ref) = env.new_global_ref(clazz) {
        let _ = RENDERER_CLASS.set(global_ref);
    }

    let result = env.register_native_methods(clazz, &methods);

    if result.is_ok() {
        debug!("register_natives : succeed");
        version
    } else {
        error!("register_natives : failed ");
        JNI_ERR
    }
}

#[no_mangle]
#[allow(non_snake_case)]
pub unsafe extern "system" fn JNI_OnLoad(jvm: JavaVM, _reserved: *mut c_void) -> jint {
    android_logger::init_once(
        Config::default()
            .with_min_level(Level::Info)
            .with_tag("CLIENT_EGL"),
    );

    debug!("JNI_OnLoad");

    let class_name: &str = "io/twoyi/Renderer";
    let jni_methods = [
        jni_method!(init, renderer_init, "(Landroid/view/Surface;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;FFI)V"),
        jni_method!(
            resetWindow,
            renderer_reset_window,
            "(Landroid/view/Surface;IIII)V"
        ),
        jni_method!(
            removeWindow,
            renderer_remove_window,
            "(Landroid/view/Surface;)V"
        ),
        jni_method!(handleTouch, handle_touch, "(Landroid/view/MotionEvent;II)V"),
        jni_method!(cancelTouch, cancel_touch, "()V"),
        jni_method!(sendKeycode, send_key_code, "(I)V"),
        jni_method!(guestFailed, guest_failed, "()I"),
        jni_method!(stopGuestNative, stop_guest, "()I"),
        jni_method!(guestInitPid, guest_init_pid, "()I"),
    ];

    let ret = register_natives(&jvm, class_name, jni_methods.as_ref());
    // The guest image check lives on its own class: it is not a renderer method and the
    // app must be able to call it without a surface.
    let check_name: &str = "io/twoyi/GuestLoaderCheck";
    let check_methods = [jni_method!(
        nativePreflight,
        guest_preflight,
        "(Ljava/lang/String;)Ljava/lang/String;"
    )];
    let check_ret = register_natives(&jvm, check_name, check_methods.as_ref());
    if check_ret == JNI_ERR {
        error!("guest loader check registration failed");
    }
    let _ = JVM_HANDLE.set(jvm);
    let _ = check_ret;
    ret
}
