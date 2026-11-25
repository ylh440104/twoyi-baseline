// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

use libc::*;
use libc::{c_char, c_int};
use ndk::event::{MotionAction, MotionEvent};
use std::mem;
use std::thread;
use std::io::{self, Write};
use std::net::Shutdown;
use std::os::unix::fs::FileTypeExt;
use std::os::unix::net::UnixListener;
use std::time::Duration;
use uinput_sys::*;

use crate::event_stream::InputQueue;
use std::sync::Mutex;
use once_cell::sync::Lazy;

use log::info;

const FF_MAX: u16 = 0x7f;

const TOUCH_DEVICE_NAME: &'static str = "vtouch";
const TOUCH_DEVICE_UNIQUE_ID: &'static str = "<vtouch 0>";

const KEY_DEVICE_NAME: &'static str = "vkey";
const KEY_DEVICE_UNIQUE_ID: &'static str = "<keyboard 0>";

#[repr(C)]
#[derive(Clone, Copy)]
struct device_info {
    name: [c_char; 80],
    driver_version: c_int,
    id: input_id,
    physical_location: [c_char; 80],
    unique_id: [c_char; 80],
    key_bitmask: [u8; (KEY_MAX as usize + 1) / 8],
    abs_bitmask: [u8; (ABS_MAX as usize + 1) / 8],
    rel_bitmask: [u8; (REL_MAX as usize + 1) / 8],
    sw_bitmask: [u8; (SW_MAX as usize + 1) / 8],
    led_bitmask: [u8; (LED_MAX as usize + 1) / 8],
    ff_bitmask: [u8; (FF_MAX as usize + 1) / 8],
    prop_bitmask: [u8; (INPUT_PROP_MAX as usize + 1) / 8],
    abs_max: [u32; ABS_CNT as usize],
    abs_min: [u32; ABS_CNT as usize],
}

const DEVICE_PREFIX_BYTES: usize = 80 + 4 + 4 * 2 + 80 + 80
    + (KEY_MAX as usize + 1) / 8
    + (ABS_MAX as usize + 1) / 8
    + (REL_MAX as usize + 1) / 8
    + (SW_MAX as usize + 1) / 8
    + (LED_MAX as usize + 1) / 8
    + (FF_MAX as usize + 1) / 8
    + (INPUT_PROP_MAX as usize + 1) / 8;
const DEVICE_WIRE_BYTES: usize = ((DEVICE_PREFIX_BYTES + 3) & !3) + 2 * ABS_CNT as usize * 4;
const _: [(); DEVICE_WIRE_BYTES] = [(); mem::size_of::<device_info>()];

impl device_info {
    fn encode(&self) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(mem::size_of::<Self>());
        bytes.extend(self.name.iter().map(|&byte| byte as u8));
        bytes.extend_from_slice(&self.driver_version.to_ne_bytes());
        for field in [self.id.bustype, self.id.vendor, self.id.product, self.id.version] {
            bytes.extend_from_slice(&field.to_ne_bytes());
        }
        bytes.extend(self.physical_location.iter().map(|&byte| byte as u8));
        bytes.extend(self.unique_id.iter().map(|&byte| byte as u8));
        for bitmap in [
            &self.key_bitmask[..], &self.abs_bitmask[..], &self.rel_bitmask[..],
            &self.sw_bitmask[..], &self.led_bitmask[..], &self.ff_bitmask[..],
            &self.prop_bitmask[..],
        ] {
            bytes.extend_from_slice(bitmap);
        }
        // Match the legacy C descriptor's alignment before the two u32 arrays.
        while bytes.len() % mem::align_of::<u32>() != 0 {
            bytes.push(0);
        }
        for axis in self.abs_max.iter().chain(self.abs_min.iter()) {
            bytes.extend_from_slice(&axis.to_ne_bytes());
        }
        debug_assert_eq!(bytes.len(), mem::size_of::<Self>());
        bytes
    }
}

#[cfg(all(target_os = "android", target_arch = "aarch64"))]
const _: [(); crate::input_wire::EVENT_BYTES] = [(); mem::size_of::<input_event>()];

fn encode_input_event(event: &input_event) -> [u8; crate::input_wire::EVENT_BYTES] {
    crate::input_wire::encode_event(
        event.time.tv_sec as i64,
        event.time.tv_usec as i64,
        event.kind,
        event.code,
        event.value,
    )
}

fn copy_to_cstr<const COUNT: usize>(data: &str, arr: &mut [u8; COUNT]) {
    if COUNT == 0 { return; }
    let len = data.len().min(COUNT - 1);
    arr[..len].copy_from_slice(&data.as_bytes()[..len]);
    arr[len] = 0;
}

const MAX_POINTERS: usize = 5;
const MAX_PENDING_EVENTS: usize = 512;

static INPUT_SENDER: Lazy<Mutex<Option<InputQueue<input_event>>>> = Lazy::new(|| { Mutex::new(None)});
static KEY_SENDER: Lazy<Mutex<Option<InputQueue<input_event>>>> = Lazy::new(|| { Mutex::new(None)});

static DEVICE_EXTENT: once_cell::sync::OnceCell<(i32, i32)> = once_cell::sync::OnceCell::new();

pub fn start_input_system(
    width: i32,
    height: i32,
    touch_path: String,
    key_path: String,
) -> io::Result<()> {
    if touch_path == key_path {
        return Err(io::Error::new(io::ErrorKind::InvalidInput,
                                  "touch and key sockets must use distinct paths"));
    }
    let touch = bind_input_socket(&touch_path)?;
    let key = match bind_input_socket(&key_path) {
        Ok(listener) => listener,
        Err(error) => {
            drop(touch);
            let _ = std::fs::remove_file(&touch_path);
            return Err(error);
        }
    };
    let _ = DEVICE_EXTENT.set((width, height));
    thread::spawn(move || touch_server(touch, width, height, &touch_path));
    thread::spawn(move || key_server(key, &key_path));
    Ok(())
}

fn bind_input_socket(path: &str) -> io::Result<UnixListener> {
    match std::fs::symlink_metadata(path) {
        Ok(metadata) if metadata.file_type().is_socket() => std::fs::remove_file(path)?,
        Ok(_) => return Err(io::Error::new(io::ErrorKind::AlreadyExists,
                                          "input socket path is occupied by a non-socket")),
        Err(error) if error.kind() == io::ErrorKind::NotFound => (),
        Err(error) => return Err(error),
    }
    UnixListener::bind(path)
}

pub fn input_event_write(
    tx: &InputQueue<input_event>,
    kind: i32,
    code: i32,
    val: i32,
) {
    let mut tp = libc::timespec { tv_sec:0, tv_nsec: 0 };
    let _ = unsafe { clock_gettime(CLOCK_MONOTONIC, &mut tp) };
    let tv = timeval {
        tv_sec: tp.tv_sec,
        tv_usec: tp.tv_nsec / 1000
    };

    let ev = input_event {
        kind: kind as u16,
        code: code as u16,
        value: val,
        time: tv,
    };
    tx.enqueue(ev);
}

static TOUCH_SLOTS: Lazy<Mutex<crate::touch_slots::TouchSlots<MAX_POINTERS>>> =
    Lazy::new(|| Mutex::new(crate::touch_slots::TouchSlots::new()));

fn release_all(fd: &InputQueue<input_event>, slots: &mut crate::touch_slots::TouchSlots<MAX_POINTERS>) {
    for (slot, id) in slots.clear().iter().enumerate() {
        if id.is_some() {
            input_event_write(fd, EV_ABS, ABS_MT_SLOT, slot as i32);
            input_event_write(fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
        }
    }
    input_event_write(fd, EV_KEY, BTN_TOUCH, 0);
    input_event_write(fd, EV_KEY, BTN_TOOL_FINGER, 0);
}

pub fn cancel_touch() {
    let sender = INPUT_SENDER.lock().unwrap();
    let mut slots = TOUCH_SLOTS.lock().unwrap();
    if slots.is_empty() {
        return;
    }
    if let Some(fd) = sender.as_ref() {
        release_all(fd, &mut slots);
        input_event_write(fd, EV_SYN, SYN_REPORT, 0);
    } else {
        slots.clear();
    }
}

pub fn handle_touch(ev: MotionEvent, width: i32, height: i32) {
    let (device_width, device_height) = match DEVICE_EXTENT.get() {
        Some(size) => *size,
        None => return,
    };
    let sender = INPUT_SENDER.lock().unwrap();
    let fd = match sender.as_ref() { Some(fd) => fd, None => return };
    let mut slots = TOUCH_SLOTS.lock().unwrap();
    match ev.action() {
        MotionAction::Cancel | MotionAction::Up => {
            release_all(fd, &mut slots);
        }
        MotionAction::Down | MotionAction::PointerDown => {
            if ev.pointer_index() >= ev.pointer_count() { return; }
            if ev.action() == MotionAction::Down {
                // A new gesture also recovers from an unobserved previous cancel.
                release_all(fd, &mut slots);
            } else if slots.is_empty() {
                // Reconnection clears the slots. Wait for the next primary down
                // instead of starting a new gesture from a secondary pointer.
                return;
            }
            let pointer = ev.pointer_at_index(ev.pointer_index());
            let id = pointer.pointer_id();
            if let Some(slot) = slots.press(id) {
                input_event_write(fd, EV_KEY, BTN_TOUCH, 1);
                input_event_write(fd, EV_KEY, BTN_TOOL_FINGER, 1);
                input_event_write(fd, EV_ABS, ABS_MT_SLOT, slot as i32);
                input_event_write(fd, EV_ABS, ABS_MT_TRACKING_ID, id);
                input_event_write(fd, EV_ABS, ABS_MT_POSITION_X, crate::touch_geometry::axis(pointer.x(), width, device_width));
                input_event_write(fd, EV_ABS, ABS_MT_POSITION_Y, crate::touch_geometry::axis(pointer.y(), height, device_height));
                input_event_write(fd, EV_ABS, ABS_MT_PRESSURE,
                    (pointer.pressure().max(0.0).min(1.0) * 80.0) as i32);
            }
        }
        MotionAction::Move => {
            for index in 0..ev.pointer_count() {
                let pointer = ev.pointer_at_index(index);
                if let Some(slot) = slots.find(pointer.pointer_id()) {
                    input_event_write(fd, EV_ABS, ABS_MT_SLOT, slot as i32);
                    input_event_write(fd, EV_ABS, ABS_MT_POSITION_X, crate::touch_geometry::axis(pointer.x(), width, device_width));
                    input_event_write(fd, EV_ABS, ABS_MT_POSITION_Y, crate::touch_geometry::axis(pointer.y(), height, device_height));
                    input_event_write(fd, EV_ABS, ABS_MT_PRESSURE,
                        (pointer.pressure().max(0.0).min(1.0) * 80.0) as i32);
                }
            }
        }
        MotionAction::PointerUp => {
            if ev.pointer_index() >= ev.pointer_count() { return; }
            let id = ev.pointer_at_index(ev.pointer_index()).pointer_id();
            if let Some(slot) = slots.release(id) {
                input_event_write(fd, EV_ABS, ABS_MT_SLOT, slot as i32);
                input_event_write(fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
                if slots.is_empty() {
                    input_event_write(fd, EV_KEY, BTN_TOUCH, 0);
                    input_event_write(fd, EV_KEY, BTN_TOOL_FINGER, 0);
                }
            }
        }
        _ => return,
    }
    input_event_write(fd, EV_SYN, SYN_REPORT, 0);
}

fn generate_touch_device(width: i32, height: i32, path: &str) -> device_info {
    let iid = input_id {
        product: 0x1,
        version: 0,
        vendor: 0,
        bustype: 0,
    };

    let mut info = device_info {
        name: unsafe { mem::zeroed() },
        driver_version: 0x1,
        id: iid,
        physical_location: unsafe { mem::zeroed() },
        unique_id: unsafe { mem::zeroed() },
        key_bitmask: unsafe { mem::zeroed() },
        abs_bitmask: unsafe { mem::zeroed() },
        rel_bitmask: unsafe { mem::zeroed() },
        sw_bitmask: unsafe { mem::zeroed() },
        led_bitmask: unsafe { mem::zeroed() },
        ff_bitmask: unsafe { mem::zeroed() },
        prop_bitmask: unsafe { mem::zeroed() },
        abs_max: unsafe { mem::zeroed() },
        abs_min: unsafe { mem::zeroed() },
    };

    copy_to_cstr(TOUCH_DEVICE_NAME, &mut info.name);
    copy_to_cstr(path, &mut info.physical_location);
    copy_to_cstr(TOUCH_DEVICE_UNIQUE_ID, &mut info.unique_id);

    let direct = INPUT_PROP_DIRECT as usize;
    info.prop_bitmask[direct / 8] |= 1 << (direct % 8);
    for code in [BTN_TOUCH, BTN_TOOL_FINGER] {
        let bit = code as usize;
        info.key_bitmask[bit / 8] |= 1 << (bit % 8);
    }
    for code in [ABS_MT_SLOT, ABS_MT_TRACKING_ID, ABS_MT_POSITION_X,
                 ABS_MT_POSITION_Y, ABS_MT_PRESSURE] {
        let bit = code as usize;
        info.abs_bitmask[bit / 8] |= 1 << (bit % 8);
    }

    info.abs_min[ABS_MT_POSITION_X as usize] = 0;
    info.abs_max[ABS_MT_POSITION_X as usize] = width as u32;

    info.abs_min[ABS_MT_POSITION_Y as usize] = 0;
    info.abs_max[ABS_MT_POSITION_Y as usize] = height as u32;

    info.abs_max[ABS_MT_TRACKING_ID as usize] = 31;

    info.abs_min[ABS_MT_SLOT as usize] = 0;
    info.abs_max[ABS_MT_SLOT as usize] = (MAX_POINTERS - 1) as u32;
    info.abs_min[ABS_MT_PRESSURE as usize] = 0;
    info.abs_max[ABS_MT_PRESSURE as usize] = 80;

    info
}

fn touch_server(listener: UnixListener, width: i32, height: i32, path: &str) {
    let device = generate_touch_device(width, height, path).encode();
    for stream in listener.incoming() {
        match stream {
            Ok(mut stream) => {
                info!("touch client connected!");

                // A stalled peer must not stop the listener from accepting a replacement.
                if stream.set_write_timeout(Some(Duration::from_secs(2))).is_err()
                    || stream.write_all(&device).is_err()
                {
                    continue;
                }

                let peer = match stream.try_clone() {
                    Ok(peer) => peer,
                    Err(error) => {
                        info!("Could not track input peer: {}", error);
                        continue;
                    }
                };
                let (queue, rx) = InputQueue::new(peer, MAX_PENDING_EVENTS);
                {
                    let mut sender = INPUT_SENDER.lock().unwrap();
                    TOUCH_SLOTS.lock().unwrap().clear();
                    *sender = Some(queue);
                }

                thread::spawn(move || {
                    crate::event_stream::forward_events(rx, |ev| {
                        stream.write_all(&encode_input_event(&ev))
                    });
                    // The producer retains a descriptor. Signal EOF when the
                    // writer exits even if that descriptor is still open.
                    let _ = stream.shutdown(Shutdown::Both);
                });
            }
            Err(_) => {
                info!("touch server error happened!");
                break;
            }
        }
    }

    info!("drop listener!");
}

fn generate_key_device(path: &str) -> device_info {
    let mut info: device_info = unsafe { std::mem::MaybeUninit::zeroed().assume_init() };

    info.driver_version = 0x1;
    info.id.product = 0x1;

    copy_to_cstr(KEY_DEVICE_NAME, &mut info.name);
    copy_to_cstr(path, &mut info.physical_location);
    copy_to_cstr(KEY_DEVICE_UNIQUE_ID, &mut info.unique_id);

    for &(_, code) in crate::key_events::KEY_MAP {
        let bit = code as usize;
        info.key_bitmask[bit / 8] |= 1 << (bit % 8);
    }

    info
}

pub fn send_key_code(keycode: i32) {
    if let Some(events) = crate::key_events::click(keycode) {
        if let Some(ref tx) = *KEY_SENDER.lock().unwrap() {
            for (kind, code, value) in events {
                input_event_write(tx, kind, code, value);
            }
        }
    }
}

fn key_server(listener: UnixListener, path: &str) {
    let device = generate_key_device(path).encode();
    for stream in listener.incoming() {
        match stream {
            Ok(mut stream) => {
                info!("key client connected!");

                // A stalled peer must not stop the listener from accepting a replacement.
                if stream.set_write_timeout(Some(Duration::from_secs(2))).is_err()
                    || stream.write_all(&device).is_err()
                {
                    continue;
                }

                let peer = match stream.try_clone() {
                    Ok(peer) => peer,
                    Err(error) => {
                        info!("Could not track input peer: {}", error);
                        continue;
                    }
                };
                let (queue, rx) = InputQueue::new(peer, MAX_PENDING_EVENTS);
                {
                    let mut sender = KEY_SENDER.lock().unwrap();
                    *sender = Some(queue);
                }

                thread::spawn(move || {
                    crate::event_stream::forward_events(rx, |ev| {
                        stream.write_all(&encode_input_event(&ev))
                    });
                    // The producer retains a descriptor. Signal EOF when the
                    // writer exits even if that descriptor is still open.
                    let _ = stream.shutdown(Shutdown::Both);
                });
            }
            Err(_) => {
                info!("key server error happened!");
                break;
            }
        }
    }
}
