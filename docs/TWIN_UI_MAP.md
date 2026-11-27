# 最新版 Twin 界面文案地图（复刻蓝图）

来源：官方 0.7.5 的资源表（`strings-default.xml`，`twin_*` 共 285 条）。
用途：作为阶段三 UI 复刻的**蓝图**——按前缀分组即可看出最新版有哪些界面与状态。
本文件只记录键名与文案，不含任何反编译代码。

## agent（9 条）

- `twin_agent_boot_failed` — Your Twin phone could not start, so this task is on hold. Ask me again once it can start.
- `twin_agent_dead_phone` — Your Twin phone is off. Go to Phone, tap Turn on again, then give me this task again.
- `twin_agent_failed` — Task not completed. See the chat for details.
- `twin_agent_name` — Twin
- `twin_agent_no_phone` — You do not have a Twin phone yet. Create one in Phone so I can use it for you.
- `twin_agent_not_wired` — The Twin connection is unavailable.
- `twin_agent_preparing` — Reading the phone screen…
- `twin_agent_queued` — OK. Your Twin phone is starting. I will begin when it is ready.
- `twin_agent_start_failed` — Could not start the Twin task: %1$s

## api（2 条）

- `twin_api_key_bad_chars` — The API key contains invalid characters. Check for non-English or full-width characters, or line breaks
- `twin_api_key_required` — Enter an API key

## attach（4 条）

- `twin_attach_camera` — Camera
- `twin_attach_file` — Files
- `twin_attach_gallery` — Photos
- `twin_attach_image` — Add image

## back（2 条）

- `twin_back` — Back
- `twin_back_to_twin` — Back to Twin

## base（1 条）

- `twin_base_url_invalid` — Enter a valid API URL starting with http:// or https://

## boot（15 条）

- `twin_boot_before_start` — Before you start
- `twin_boot_failed` — Could not start the Twin phone. Please try again later.
- `twin_boot_opening` — Hi, I am your Twin. First, create a phone for me. You can use it like any other phone. Connect me to a model too, and you can hand over what you are doing whenever you want.
- `twin_boot_preparing` — Preparing the system…
- `twin_boot_starting` — Starting…
- `twin_boot_step1_action` — Create
- `twin_boot_step1_desc` — A separate, empty phone. You choose what to install and which accounts to use.
- `twin_boot_step1_done_desc` — Your Twin phone is ready. Open it anytime in Phone.
- `twin_boot_step1_done_title` — Create a Twin phone · Done
- `twin_boot_step1_title` — Create a Twin phone
- `twin_boot_step2_action` — Connect
- `twin_boot_step2_desc` — Needed only to chat with me and hand tasks over. Set up an API key, or sign in with a provider account. Your key stays on this device.
- `twin_boot_step2_done_desc` — Connected to %1$s. Your key stays on this device. You can change it in Settings.
- `twin_boot_step2_done_title` — Connect a model · Done
- `twin_boot_step2_title` — Connect a model (optional)

## camera（1 条）

- `twin_camera_denied` — Camera permission is needed to take a photo

## cancelled（1 条）

- `twin_cancelled_header` — Cancelled

## chat（5 条）

- `twin_chat_failed` — No reply received. Please try again later.
- `twin_chat_ready` — All set. Tell me what you want to do. You can also use the phone yourself, come back here and say a word, and I will continue from that screen.
- `twin_chat_today` — Today
- `twin_chat_typing` — …
- `twin_chat_yesterday_at` — Yesterday %1$s

## connect（1 条）

- `twin_connect_failed` — Could not connect. Please try again later.

## connected（1 条）

- `twin_connected` — Connected to %1$s

## connection（1 条）

- `twin_connection_expired` — The connection has expired. Please reconnect the model.

## control（5 条）

- `twin_control_agent` — Twin is working
- `twin_control_need` — Needs you
- `twin_control_stopping` — Stopping
- `twin_control_taken_over` — You have taken over
- `twin_control_taking_over` — Taking over

## copied（1 条）

- `twin_copied` — Copied

## copy（1 条）

- `twin_copy` — Copy

## create（2 条）

- `twin_create_cost` — Takes about 1 minute and 4 GB of storage
- `twin_create_phone` — Create a Twin phone

## done（1 条）

- `twin_done_header` — Completed

## failed（1 条）

- `twin_failed_header` — Not completed

## finish（1 条）

- `twin_finish_task_first` — Finish the current task first

## guest（4 条）

- `twin_guest_died_reason` — The Twin phone shut down before the task was finished.
- `twin_guest_died_title` — Twin phone is off
- `twin_guest_reboot` — Turn on again
- `twin_guest_reboot_desc` — Restarts the Twin app. Your chat history is kept.

## hand（1 条）

- `twin_hand_back` — Continue

## handback（1 条）

- `twin_handback_capsule` — Hand back to Twin

## host（31 条）

- `twin_host_battery_body` — "The system pauses Twoyi once the screen goes off, and the Twin's tasks stop with it. On the next page, set Battery saver to No restrictions."
- `twin_host_battery_later` — Later
- `twin_host_battery_open` — Open settings
- `twin_host_battery_title` — One more step
- `twin_host_died_title` — Background screen is closed
- `twin_host_got_it` — Got it
- `twin_host_move_failed` — No app on this screen to move.
- `twin_host_move_to_main` — Continue on main screen
- `twin_host_no_root_body` — Allow Twoyi in your root manager (Magisk, KernelSU…), then try again.
- `twin_host_no_root_title` — Root not granted
- `twin_host_notification_title` — "Twin's background screen is on"
- `twin_host_open_failed` — "Couldn't open this app"
- `twin_host_reopen` — Open again
- `twin_host_reopen_desc` — "If it won't open, check that your root manager or Shizuku still allows Twoyi; Shizuku needs starting again after each reboot. Your chat history is kept."
- `twin_host_shizuku_denied_body` — "Allow Twoyi in Shizuku's list of apps, then try again."
- `twin_host_shizuku_denied_title` — Shizuku did not allow Twoyi
- `twin_host_shizuku_off_body` — Install Shizuku and start it, then try again. Without root, Shizuku needs starting again after each reboot.
- `twin_host_shizuku_off_title` — Shizuku is not running
- `twin_host_starting` — Opening the background screen…
- `twin_host_switch_access_body` — The background screen opens again, and the apps on it close.
- `twin_host_switch_access_title` — Switch how the Twin reaches this phone?
- `twin_host_switch_back_body` — The Twin stops using this phone, and the apps on the background screen close.
- `twin_host_switch_back_title` — Go back to the Twin phone?
- `twin_host_switch_battery` — So tasks keep running with the screen off, Twoyi sets its own battery saver to No restrictions, and restores it when you go back to the Twin phone.
- `twin_host_switch_body` — "The Twin opens your phone's apps on a background screen and acts with your accounts, out of the way of what you're doing. Needs root. The Twin phone shuts down; everything in it is kept."
- `twin_host_switch_body_shizuku` — "The Twin opens your phone's apps on a background screen and acts with your accounts, out of the way of what you're doing. Start Shizuku first; Twoyi then asks Shizuku for permission. The Twin phone s
- `twin_host_switch_cancel` — Cancel
- `twin_host_switch_confirm` — Switch
- `twin_host_switch_title` — Let the Twin use this phone?
- `twin_host_switching` — Switching…
- `twin_host_task_running` — Finish the current task first

## hostbar（4 条）

- `twin_hostbar_agent` — Twin is in control
- `twin_hostbar_sensitive` — Sensitive input · Twin paused
- `twin_hostbar_user` — You are in control
- `twin_hostbar_user_takeover` — You are in control · Taking over

## http（1 条）

- `twin_http_error` — The service returned an error (HTTP %1$d)

## image（2 条）

- `twin_image` — Image
- `twin_image_unreadable` — "Couldn't read that image"

## input（6 条）

- `twin_input_hint` — Talk to your Twin
- `twin_input_hint_disabled` — Connect a model to start chatting
- `twin_input_hint_need` — Or just say what you think
- `twin_input_hint_need_handoff` — "Or tell your Twin when it's done"
- `twin_input_hint_takeover` — Say something to hand the phone back
- `twin_input_hint_task` — Add to the current task; applies on the next step

## key（2 条）

- `twin_key_invalid` — The API key is invalid or lacks permission
- `twin_key_save_failed` — Could not securely save the key on this device. Please try again.

## live（2 条）

- `twin_live_foot` — This is a live view. Touches will not control this phone.
- `twin_live_goal_label` — Current goal

## memory（8 条）

- `twin_memory_clear` — Clear all
- `twin_memory_clear_body` — "Your Twin will forget everything it remembered. This can't be undone."
- `twin_memory_clear_title` — Clear all memories?
- `twin_memory_delete` — Delete
- `twin_memory_empty` — "Your Twin hasn't remembered anything yet. Tell it things worth keeping while you chat, like what to call you, addresses you use or what you prefer, and it will remember them."
- `twin_memory_keep` — Keep
- `twin_memory_note` — Memories stay on this phone and go with each conversation to the model you connected. To make your Twin forget something, delete it here or just tell it.
- `twin_memory_title` — Memory

## mini（1 条）

- `twin_mini_expand` — Show the phone

## model（2 条）

- `twin_model_not_found` — Model “%1$s” was not found. Check the model name
- `twin_model_not_wired` — The model connection is unavailable.

## models（1 条）

- `twin_models_empty` — No models were returned. Enter the model name manually.

## need（16 条）

- `twin_need_accept` — OK
- `twin_need_consequence_delete` — It may not be recoverable
- `twin_need_consequence_pay` — This will spend money
- `twin_need_consequence_send` — "Others will see it once it's sent"
- `twin_need_go_phone` — Go to phone
- `twin_need_handoff_default` — Your turn
- `twin_need_handoff_sub` — Handle it on the phone, then tap Continue
- `twin_need_reject` — No
- `twin_need_reject_tap` — Not now
- `twin_need_row_accepted` — You agreed
- `twin_need_row_asked` — Asked you: %1$s
- `twin_need_row_declined` — You said no
- `twin_need_row_handled` — You handled it
- `twin_need_row_replied` — You replied: %1$s
- `twin_need_tap_question` — Tap “%1$s”?
- `twin_need_waiting` — Your Twin is waiting for your answer

## network（1 条）

- `twin_network_error` — Cannot connect. Check your network or API URL.

## new（1 条）

- `twin_new_topic` — New topic

## notif（5 条）

- `twin_notif_channel_need` — Needs you
- `twin_notif_channel_progress` — Task progress
- `twin_notif_channel_result` — Task results
- `twin_notif_need_open_app` — Involves payment — open the app to confirm
- `twin_notif_need_title` — Your Twin needs you

## parse（1 条）

- `twin_parse_error` — Could not read the model response. Please try again later.

## pause（1 条）

- `twin_pause` — Pause

## phone（6 条）

- `twin_phone_empty_desc` — This will be a separate phone. It will not automatically copy apps, accounts, or private data from your main phone.
- `twin_phone_empty_desc_em` — You choose what to share with your Twin.
- `twin_phone_empty_foot` — Install apps and sign in on your Twin phone before asking your Twin to use them.
- `twin_phone_empty_title` — No Twin phone yet
- `twin_phone_exit_fullscreen` — Exit full screen
- `twin_phone_fullscreen` — Use phone in full screen

## pip（1 条）

- `twin_pip_pausing` — Pausing

## preset（3 条）

- `twin_preset_custom_anthropic` — Custom (Anthropic-compatible)
- `twin_preset_custom_openai` — Custom (OpenAI-compatible)
- `twin_preset_custom_openai_responses` — Custom (OpenAI Responses)

## provider（25 条）

- `twin_provider_active` — In use
- `twin_provider_add` — Add provider
- `twin_provider_advanced` — Advanced
- `twin_provider_api_key` — API key
- `twin_provider_base_url` — API URL
- `twin_provider_choose` — Choose a provider
- `twin_provider_connect` — Connect
- `twin_provider_delete` — Delete this connection
- `twin_provider_delete_confirm` — Tap again to delete
- `twin_provider_desc` — Your Twin needs a language model to think. Your key stays on this device.
- `twin_provider_edit` — Edit
- `twin_provider_fetch_models` — Fetch models
- `twin_provider_fetching_models` — Fetching…
- `twin_provider_get_key` — Get an API key
- `twin_provider_key_keep` — Leave blank to keep the current key
- `twin_provider_key_local` — Your API key is encrypted and stored only on this device. It is not uploaded.
- `twin_provider_key_optional` — Optional for local services
- `twin_provider_label` — Name (optional)
- `twin_provider_model` — Model name
- `twin_provider_model_hint` — For example, %1$s
- `twin_provider_oauth` — Sign in with OAuth
- `twin_provider_oauth_desc` — Sign in with your provider account
- `twin_provider_oauth_stub` — OAuth is not supported yet. Please use an API key.
- `twin_provider_title` — Connect a model
- `twin_provider_verifying` — Verifying…

## rate（1 条）

- `twin_rate_limited` — Too many requests. Please try again later.

## record（1 条）

- `twin_record_empty` — Just started, no steps yet

## remove（1 条）

- `twin_remove_image` — Remove image

## reply（1 条）

- `twin_reply_cut_off` — The reply ended early (output limit or content filter) and is incomplete. Try again or rephrase.

## resume（1 条）

- `twin_resume` — Resume

## retry（1 条）

- `twin_retry` — Retry

## scroll（1 条）

- `twin_scroll_to_latest` — Latest messages

## send（1 条）

- `twin_send` — Send

## session（2 条）

- `twin_session_channel_name` — Twin phone
- `twin_session_notification_title` — Twin phone is running

## settings（33 条）

- `twin_settings_confirm_risky` — Ask before risky actions
- `twin_settings_confirm_risky_desc` — Stops to ask before deleting, paying, sending or posting
- `twin_settings_files` — Files
- `twin_settings_files_desc` — "Browse the Twin phone's files in the Files app"
- `twin_settings_group_help` — Help
- `twin_settings_group_host` — Phone
- `twin_settings_group_phone` — Twin phone
- `twin_settings_group_twin` — Twin
- `twin_settings_host_battery` — Battery saver
- `twin_settings_host_battery_desc` — Tasks stop when the screen goes off. Set Twoyi to No restrictions.
- `twin_settings_import_app` — Import apps
- `twin_settings_import_app_desc` — Install apps from this phone. Their data and sign-ins are not copied.
- `twin_settings_memory` — Memory
- `twin_settings_memory_empty` — Nothing remembered yet
- `twin_settings_model` — Model
- `twin_settings_model_disconnected` — Not connected
- `twin_settings_needs_boot` — Available once the Twin phone is on
- `twin_settings_needs_phone` — Available once you create the Twin phone
- `twin_settings_phone_host` — This phone (root)
- `twin_settings_phone_mode` — Phone the Twin uses
- `twin_settings_phone_shizuku` — This phone (Shizuku)
- `twin_settings_phone_twin` — Twin phone
- `twin_settings_pip` — Picture-in-picture when you leave
- `twin_settings_pip_always` — Always
- `twin_settings_pip_off` — Off
- `twin_settings_pip_task` — While a task runs
- `twin_settings_see_screen` — See the screen
- `twin_settings_see_screen_desc` — Sends screenshots to the model when an app has no readable interface, such as games and video, or when it asks. Needs a model that can see images
- `twin_settings_send_log` — Send logs
- `twin_settings_send_log_desc` — Package diagnostic logs and recent Twin task runs, and choose how to send them
- `twin_settings_sensitive_guard` — Sensitive input protection
- `twin_settings_sensitive_guard_desc` — You enter all passwords, verification codes, and payment details. They are never sent to the model or saved in the activity log.
- `twin_settings_title` — Settings

## status（12 条）

- `twin_status_doing` — %1$s…
- `twin_status_done` — Done
- `twin_status_done_sub` — See the result in Twin
- `twin_status_paused` — Paused
- `twin_status_running` — Working
- `twin_status_steps` — Step %1$d
- `twin_status_stopping_hand` — Your Twin is stopping…
- `twin_status_stopping_hand_sub` — You get the phone after this step
- `twin_status_tap_hint_sub` — Tap Take over to use it
- `twin_status_user` — "You're in control"
- `twin_status_user_chat_sub` — "When you're done, tap Continue or say something here"
- `twin_status_user_paused_at` — Your Twin paused at step %1$d

## step（21 条）

- `twin_step_agent` — Step %1$d · %2$s
- `twin_step_back` — Go back
- `twin_step_button` — Press %1$s
- `twin_step_detail` — See why and how long
- `twin_step_enter` — Press Enter
- `twin_step_home` — Go to the home screen
- `twin_step_long_press` — Long-press “%1$s”
- `twin_step_long_press_somewhere` — Long-press the screen
- `twin_step_open_app` — Open %1$s
- `twin_step_other` — Run %1$s
- `twin_step_swipe_down` — Swipe down
- `twin_step_swipe_left` — Swipe left
- `twin_step_swipe_right` — Swipe right
- `twin_step_swipe_up` — Swipe up
- `twin_step_tap` — Tap “%1$s”
- `twin_step_tap_somewhere` — Tap the screen
- `twin_step_took_minutes` — Took %1$d min %2$d s
- `twin_step_took_seconds` — Took %1$s s
- `twin_step_type` — Type “%1$s”
- `twin_step_type_secret` — Enter a password
- `twin_step_wait` — Wait for the page

## steps（1 条）

- `twin_steps_collapse` — Collapse

## stop（2 条）

- `twin_stop_reply` — Stop replying
- `twin_stop_task` — Stop task

## stopping（1 条）

- `twin_stopping_task` — Stopping…

## tab（2 条）

- `twin_tab_phone` — Phone
- `twin_tab_twin` — Twin

## takeover（1 条）

- `twin_takeover` — Take over

## task（6 条）

- `twin_task_busy` — The current task is still running. Take over or finish it before giving me a new task.
- `twin_task_cancelled_reason` — Task cancelled. Actions already performed are kept.
- `twin_task_default_ack` — OK, I will do that now.
- `twin_task_message_applied` — Got your note: %1$s
- `twin_task_reply` — Reply: %1$s
- `twin_task_step_limit_reason` — Task stopped at the %1$d-step limit. Actions already performed are kept.

## thinking（2 条）

- `twin_thinking_done` — Thought
- `twin_thinking_live` — Thinking…

## tools（6 条）

- `twin_tools_change_model` — Switch model
- `twin_tools_got_it` — Got it
- `twin_tools_missing_body` — Connected. When asked to open Settings on the phone, it only replied in words and did not start a task, so it may not be able to operate the phone for you. To let Twin do things, switch to a model tha
- `twin_tools_missing_title` — “%1$s” may only be able to chat
- `twin_tools_rejected_body` — Connected, but the service does not accept tool calls. Twin can chat with you but cannot operate the phone. To let Twin do things, switch to a model that supports tool calling.
- `twin_tools_rejected_title` — “%1$s” can only chat

## update（7 条）

- `twin_update_check` — Check for updates
- `twin_update_checking` — Checking…
- `twin_update_download` — Download
- `twin_update_failed` — Could not check for updates. Try again later.
- `twin_update_later` — Later
- `twin_update_latest` — You are up to date
- `twin_update_title` — New version %1$s
