/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package io.twoyi.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.provider.DocumentsContract;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.util.Pair;

import com.microsoft.appcenter.crashes.Crashes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.twoyi.GuestInstanceInstaller;
import io.twoyi.GuestInstanceManager;
import io.twoyi.GuestInstanceRegistry;
import io.twoyi.R;
import io.twoyi.RomPreparation;
import io.twoyi.TwoyiStatusManager;
import io.twoyi.utils.AppKV;
import io.twoyi.utils.GuestDataWipe;
import io.twoyi.utils.LogEvents;
import io.twoyi.utils.RomManager;
import io.twoyi.utils.UIHelper;

/**
 * @author weishu
 * @date 2022/1/2.
 */

public class SettingsActivity extends AppCompatActivity {

    private static final int REQUEST_GET_FILE = 1000;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_settings);
        SettingsFragment settingsFragment = new SettingsFragment();
        getFragmentManager().beginTransaction()
                .replace(R.id.settingsFrameLayout, settingsFragment)
                .commit();

        ActionBar actionBar = getSupportActionBar();

        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setBackgroundDrawable(getResources().getDrawable(R.color.colorPrimary));
            actionBar.setTitle(R.string.title_settings);
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragment {
        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.pref_settings);
        }

        private Preference findPreference(@StringRes int id) {
            String key = getString(id);
            return findPreference(key);
        }

        private final Handler statusHandler = new Handler(Looper.getMainLooper());
        private final Runnable refreshGuestPhase = new Runnable() {
            @Override public void run() {
                Preference status = findPreference("guest_instance_status");
                switch (TwoyiStatusManager.getInstance().phase()) {
                    case STARTING: status.setSummary(R.string.guest_phase_starting); break;
                    case RUNNING: status.setSummary(R.string.guest_phase_running); break;
                    case FAILED: status.setSummary(R.string.guest_phase_failed); break;
                    default: status.setSummary(R.string.guest_phase_stopped);
                }
                statusHandler.postDelayed(this, 1000);
            }
        };

        @Override public void onResume() {
            super.onResume();
            refreshGuestPhase.run();
        }

        @Override public void onPause() {
            statusHandler.removeCallbacks(refreshGuestPhase);
            super.onPause();
        }

        private void refreshInstances() {
            Preference instances = findPreference("guest_instances");
            Context app = getActivity() == null ? null : getActivity().getApplicationContext();
            if (app == null) return;
            UIHelper.defer().when(() -> {
                GuestInstanceRegistry registry = GuestInstanceRegistry.load(
                        GuestInstanceRegistry.defaultPath(app.getDataDir().toPath()));
                StringBuilder summary = new StringBuilder();
                for (String id : registry.instances()) {
                    if (summary.length() > 0) summary.append('\n');
                    summary.append(id).append(" \u2192 slot ").append(registry.slotOf(id))
                            .append(" (").append(registry.stateOf(id)).append(')');
                }
                return summary.length() == 0 ? app.getString(R.string.guest_instances_empty) : summary.toString();
            }).done(instances::setSummary
            ).fail(failure -> instances.setSummary(
                    app.getString(R.string.guest_instances_unreadable, failure.getMessage())));
        }

        private static String nextInstanceId(GuestInstanceRegistry registry) {
            for (int n = 2; n < 1000; n++) {
                String candidate = "twin-" + n;
                if (!registry.instances().contains(candidate)) return candidate;
            }
            throw new IllegalStateException("No free instance name");
        }

        private void chooseInstanceToPrepare() {
            Activity activity = getActivity();
            if (activity == null) return;
            Context app = activity.getApplicationContext();
            UIHelper.defer().when(() -> GuestInstanceRegistry.load(
                    GuestInstanceRegistry.defaultPath(app.getDataDir().toPath())).instances()
            ).done(ids -> {
                if (getActivity() != activity) return;
                if (ids.isEmpty()) {
                    Toast.makeText(app, R.string.guest_instances_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.guest_instance_prepare)
                        .setItems(ids.toArray(new String[0]),
                                (dialog, which) -> installForInstance(activity, ids.get(which)))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }).fail(failure -> {
                if (getActivity() != activity) return;
                Toast.makeText(app, app.getString(R.string.guest_instances_unreadable, failure.getMessage()),
                        Toast.LENGTH_LONG).show();
            });
        }

        private void installForInstance(Activity activity, String instanceId) {
            Context app = activity.getApplicationContext();
            Toast.makeText(app, R.string.guest_instance_prepare_running, Toast.LENGTH_SHORT).show();
            UIHelper.defer().when(() -> GuestInstanceInstaller.installBundledRom(app, instanceId)
            ).done(ready -> {
                if (getActivity() != activity) return;
                Toast.makeText(app, app.getString(R.string.guest_instance_prepare_done, instanceId,
                        String.valueOf(ready)), Toast.LENGTH_LONG).show();
                refreshInstances();
            }).fail(failure -> {
                if (getActivity() != activity) return;
                Toast.makeText(app, app.getString(R.string.install_failed_reason, failure.getMessage()),
                        Toast.LENGTH_LONG).show();
                refreshInstances();
            });
        }

        private void createInstance() {
            Activity activity = getActivity();
            if (activity == null) return;
            Context app = activity.getApplicationContext();
            UIHelper.defer().when(() -> {
                GuestInstanceRegistry registry = GuestInstanceRegistry.load(
                        GuestInstanceRegistry.defaultPath(app.getDataDir().toPath()));
                String id = nextInstanceId(registry);
                int slot = GuestInstanceManager.create(app.getDataDir().toPath(), registry, id);
                return app.getString(R.string.guest_instance_created, id, slot);
            }).done(message -> {
                if (getActivity() != activity) return;
                Toast.makeText(app, message, Toast.LENGTH_LONG).show();
                refreshInstances();
            }).fail(failure -> {
                if (getActivity() != activity) return;
                Toast.makeText(app, app.getString(R.string.install_failed_reason, failure.getMessage()),
                        Toast.LENGTH_LONG).show();
            });
        }

        private void chooseInstanceToRelease() {
            Activity activity = getActivity();
            if (activity == null) return;
            Context app = activity.getApplicationContext();
            UIHelper.defer().when(() -> GuestInstanceRegistry.load(
                    GuestInstanceRegistry.defaultPath(app.getDataDir().toPath())).instances()
            ).done(ids -> {
                if (getActivity() != activity) return;
                if (ids.isEmpty()) {
                    Toast.makeText(app, R.string.guest_instances_empty, Toast.LENGTH_SHORT).show();
                    return;
                }
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.guest_instance_release)
                        .setItems(ids.toArray(new String[0]),
                                (dialog, which) -> confirmRelease(activity, ids.get(which)))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }).fail(failure -> {
                if (getActivity() != activity) return;
                Toast.makeText(app, app.getString(R.string.guest_instances_unreadable, failure.getMessage()),
                        Toast.LENGTH_LONG).show();
            });
        }

        private void confirmRelease(Activity activity, String instanceId) {
            Context app = activity.getApplicationContext();
            UIHelper.getDialogBuilder(activity)
                    .setTitle(android.R.string.dialog_alert_title)
                    .setMessage(activity.getString(R.string.guest_instance_release_confirm, instanceId))
                    .setPositiveButton(R.string.i_confirm_it, (dialog, which) -> {
                        dialog.dismiss();
                        UIHelper.defer().when(() -> {
                            GuestInstanceRegistry registry = GuestInstanceRegistry.load(
                                    GuestInstanceRegistry.defaultPath(app.getDataDir().toPath()));
                            List<String> removed = GuestInstanceManager.release(
                                    app.getDataDir().toPath(), registry, instanceId, true);
                            return app.getString(R.string.guest_instance_released,
                                    instanceId, removed.size());
                        }).done(message -> {
                            if (getActivity() != activity) return;
                            Toast.makeText(app, message, Toast.LENGTH_LONG).show();
                            refreshInstances();
                        }).fail(failure -> {
                            if (getActivity() != activity) return;
                            Toast.makeText(app, app.getString(R.string.install_failed_reason,
                                    failure.getMessage()), Toast.LENGTH_LONG).show();
                        });
                    })
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss())
                    .show();
        }

        @Override
        public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);

            EditTextPreference guestName = (EditTextPreference) findPreference(AppKV.GUEST_NAME);
            String savedName = AppKV.getGuestName(getActivity());
            guestName.setText(savedName);
            guestName.setSummary(savedName);
            guestName.setOnPreferenceChangeListener((preference, value) -> {
                Activity activity = getActivity();
                if (activity == null) return false;
                if (!AppKV.setGuestName(activity, (String) value)) {
                    Toast.makeText(activity, R.string.guest_instance_name_error, Toast.LENGTH_SHORT).show();
                    return false;
                }
                String title = AppKV.getGuestName(activity);
                guestName.setText(title);
                guestName.setSummary(title);
                return false; // Already saved, including normalization and default restoration.
            });

            Preference guestInstances = findPreference("guest_instances");
            guestInstances.setOnPreferenceClickListener(preference -> {
                refreshInstances();
                return true;
            });
            refreshInstances();

            Preference createPreference = findPreference("guest_instance_create");
            if (createPreference != null) {
                createPreference.setOnPreferenceClickListener(preference -> {
                    createInstance();
                    return true;
                });
            }
            Preference preparePreference = findPreference("guest_instance_prepare");
            if (preparePreference != null) {
                preparePreference.setOnPreferenceClickListener(preference -> {
                    chooseInstanceToPrepare();
                    return true;
                });
            }
            Preference releasePreference = findPreference("guest_instance_release");
            if (releasePreference != null) {
                releasePreference.setOnPreferenceClickListener(preference -> {
                    chooseInstanceToRelease();
                    return true;
                });
            }

            Preference importApp = findPreference(R.string.settings_key_import_app);
            Preference export = findPreference(R.string.settings_key_manage_files);

            Preference shutdown = findPreference(R.string.settings_key_shutdown);
            Preference reboot = findPreference(R.string.settings_key_reboot);
            Preference replaceRom = findPreference(R.string.settings_key_replace_rom);
            Preference factoryReset = findPreference(R.string.settings_key_factory_reset);

            Preference donate = findPreference(R.string.settings_key_donate);
            Preference sendLog = findPreference(R.string.settings_key_sendlog);
            Preference about = findPreference(R.string.settings_key_about);

            importApp.setOnPreferenceClickListener(preference -> {
                UIHelper.startActivity(getContext(), SelectAppActivity.class);
                return true;
            });

            export.setOnPreferenceClickListener(preference -> {
                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setType(DocumentsContract.Document.MIME_TYPE_DIR);
                startActivity(intent);
                return true;
            });

            shutdown.setOnPreferenceClickListener(preference -> {
                RomManager.shutdown(getActivity());
                return true;
            });

            reboot.setOnPreferenceClickListener(preference -> {
                RomManager.reboot(getActivity());
                return true;
            });

            replaceRom.setOnPreferenceClickListener(preference -> {

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);

                // you can only select one rootfs
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
                intent.setType("*/*"); // apk file
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                try {
                    startActivityForResult(intent, REQUEST_GET_FILE);
                } catch (Throwable ignored) {
                    Toast.makeText(getContext(), "Error", Toast.LENGTH_SHORT).show();
                }
                return true;
            });

            Preference wipeAll = findPreference(R.string.settings_key_wipe_all_data);
            wipeAll.setOnPreferenceClickListener(preference -> {
                Activity activity = getActivity();
                if (activity == null) return false;
                UIHelper.getDialogBuilder(activity)
                        .setTitle(android.R.string.dialog_alert_title)
                        .setMessage(R.string.guest_wipe_confirm_message)
                        .setPositiveButton(R.string.i_confirm_it, (dialog, which) -> {
                            dialog.dismiss();
                            wipeGuestData(activity, wipeAll);
                        })
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss())
                        .show();
                return true;
            });

            factoryReset.setOnPreferenceClickListener(preference -> {
                UIHelper.getDialogBuilder(getActivity())
                        .setTitle(android.R.string.dialog_alert_title)
                        .setMessage(R.string.factory_reset_confirm_message)
                        .setPositiveButton(R.string.i_confirm_it, (dialog, which) -> {
                            Activity activity = getActivity();
                            if (activity == null) return;
                            if (!AppKV.requestRomInstall(activity, false)) {
                                Toast.makeText(activity, R.string.guest_rom_request_failed, Toast.LENGTH_SHORT).show();
                                return;
                            }
                            dialog.dismiss();
                            RomManager.reboot(activity);
                        })
                        .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss())
                        .show();
                return true;
            });

            donate.setOnPreferenceClickListener(preference -> {
                Context context = getContext();
                if (context instanceof Activity) {
                    UIHelper.showDonateDialog((Activity) context);
                    return true;
                }
                return false;
            });

            sendLog.setOnPreferenceClickListener(preference -> {
                Activity activity = getActivity();
                if (activity == null) return false;
                Context app = activity.getApplicationContext();
                preference.setEnabled(false);
                UIHelper.defer().when(() -> {
                    return LogEvents.createShareableReport(app);
                }).done(uri -> {
                    preference.setEnabled(true);
                    if (activity.isFinishing() || activity.isDestroyed() || getActivity() != activity) return;
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                    shareIntent.setDataAndType(uri, "application/zip");
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    activity.startActivity(Intent.createChooser(shareIntent,
                            activity.getString(R.string.settings_key_sendlog)));
                }).fail(failure -> {
                    preference.setEnabled(true);
                    Crashes.trackError(failure);
                    if (!activity.isFinishing() && !activity.isDestroyed() && getActivity() == activity) {
                        Toast.makeText(activity, activity.getString(R.string.install_failed_reason,
                                failure.getMessage()), Toast.LENGTH_SHORT).show();
                    }
                });
                return true;
            });

            about.setOnPreferenceClickListener(preference -> {
                UIHelper.startActivity(getContext(), AboutActivity.class);
                return true;
            });
        }


        private void wipeGuestData(Activity activity, Preference preference) {
            preference.setEnabled(false);
            Context app = activity.getApplicationContext();
            UIHelper.defer().when(() -> {
                if (TwoyiStatusManager.getInstance().isStarted() || RomPreparation.isExtracting()) {
                    throw new IllegalStateException(app.getString(R.string.guest_maintenance_busy));
                }
                java.io.File slotBase = io.twoyi.utils.RomManager.getSlotBaseDir(app);
                return GuestDataWipe.wipe(app.getDataDir().toPath(), slotBase.toPath().resolve("rootfs"));
            }).done(removed -> {
                preference.setEnabled(true);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    Toast.makeText(activity, R.string.guest_wipe_complete, Toast.LENGTH_LONG).show();
                    // Recreating the process clears the process-wide guest singletons.
                    activity.finish();
                    new Handler(Looper.getMainLooper()).postDelayed(
                            () -> android.os.Process.killProcess(android.os.Process.myPid()), 800);
                });
            }).fail(failure -> {
                preference.setEnabled(true);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    Toast.makeText(activity, activity.getString(R.string.install_failed_reason,
                            failure.getMessage()), Toast.LENGTH_LONG).show();
                });
            });
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
            super.onActivityResult(requestCode, resultCode, data);

            if (!(requestCode == REQUEST_GET_FILE && resultCode == Activity.RESULT_OK)) {
                return;
            }

            if (data == null) {
                return;
            }

            Uri uri = data.getData();
            if (uri == null) {
                return;
            }

            Activity activity = getActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                return;
            }
            ProgressDialog dialog = UIHelper.getProgressDialog(activity);
            dialog.setCancelable(false);
            dialog.show();

            // start copy 3rd rom
            UIHelper.defer().when(() -> {

                File candidate = File.createTempFile("rom-import-", ".7z", activity.getFilesDir());
                try {
                    ContentResolver contentResolver = activity.getContentResolver();
                    try (InputStream inputStream = contentResolver.openInputStream(uri);
                         OutputStream os = new FileOutputStream(candidate)) {
                        if (inputStream == null) throw new IOException("Unable to open ROM");
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = inputStream.read(buffer)) != -1) {
                            os.write(buffer, 0, count);
                        }
                    }
                    return Pair.create(candidate, RomManager.getRomInfo(candidate));
                } catch (IOException | RuntimeException e) {
                    candidate.delete();
                    throw e;
                }
            }).done(result -> {

                File rootfs3rd = result.first;
                RomManager.RomInfo romInfo = result.second;
                UIHelper.dismiss(dialog);
                if (activity.isFinishing() || activity.isDestroyed() || getActivity() != activity) {
                    rootfs3rd.delete();
                    return;
                }

                // copy finished, show dialog confirm
                if (romInfo.isValid()) {

                    String author = romInfo.author;
                    if ("weishu".equalsIgnoreCase(author) || "twoyi".equalsIgnoreCase(author)) {
                        Toast.makeText(activity, R.string.replace_rom_unofficial_tips, Toast.LENGTH_SHORT).show();
                        rootfs3rd.delete();
                        return;

                    }
                    UIHelper.getDialogBuilder(activity)
                            .setTitle(R.string.replace_rom_confirm_title)
                            .setMessage(getString(R.string.replace_rom_confirm_message, author, romInfo.version, romInfo.desc))
                            .setPositiveButton(R.string.i_confirm_it, (dialog1, which) -> {
                                try {
                                    Files.move(rootfs3rd.toPath(), RomManager.get3rdRootfsFile(activity).toPath(),
                                            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                } catch (IOException moveFailure) {
                                    rootfs3rd.delete();
                                    Toast.makeText(activity, R.string.replace_rom_invalid, Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                if (!AppKV.requestRomInstall(activity, true)) {
                                    Toast.makeText(activity, R.string.guest_rom_request_failed, Toast.LENGTH_SHORT).show();
                                    return;
                                }

                                dialog1.dismiss();

                                RomManager.reboot(getActivity());
                            })
                            .setOnDismissListener(ignored -> rootfs3rd.delete())
                            .setNegativeButton(android.R.string.cancel, (dialog12, which) -> dialog12.dismiss())
                            .show();
                } else {
                    Toast.makeText(activity, R.string.replace_rom_invalid, Toast.LENGTH_SHORT).show();
                    rootfs3rd.delete();
                }
            }).fail(result -> activity.runOnUiThread(() -> {
                UIHelper.dismiss(dialog);
                if (activity.isFinishing() || activity.isDestroyed() || getActivity() != activity) {
                    return;
                }
                Toast.makeText(activity, activity.getString(R.string.install_failed_reason, result.getMessage()),
                        Toast.LENGTH_SHORT).show();
                activity.finish();
            }));

        }
    }
}
