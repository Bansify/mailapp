# MailApp (Android)

A full-featured Material 3 email client built with Kotlin, Jetpack Compose, Navigation Compose, and Room Database.

## Features
- **Inbox & Smart Categories**: Primary, Promotions, Social, Updates, All Inboxes, Starred, Snoozed, Important, Sent, Scheduled, Drafts, All Mail, Spam, Trash, and custom labels.
- **Full Conversation Thread Detail**: Collapsible/expandable message cards, expandable `"to me"` header metadata (`From`, `To`, `Cc`, `Date`, `TLS encryption`), attachment preview dialog, Snooze, Label picker, and Reply / Reply All / Forward actions.
- **Swipe & Multi-Select Actions**: Customizable left/right swipe gestures (Archive, Delete, Mark Read/Unread) with Snackbar Undo, plus long-press / avatar-tap multi-select action bar.
- **Rich Compose Experience**: Multi-account `From` selector, recipient chips with contact autocomplete, expandable `Cc`, formatting helpers, zero-permission Photo Picker & Document Picker attachments, Confidential mode, Schedule Send, and auto-saved Drafts.
- **Live Search & Filters**: Instant search across senders, subjects, and message bodies with filter chips (`Is unread`, `Has attachment`, `Starred`, `Sent by me`) and recent search history.
- **Multi-Account Switcher & Meet Tab**: Seamless switching between accounts with cloud storage meter, add-account flow, and an interactive Meet tab for instant meeting codes.
- **Adaptive Tablet & Dark Mode Support**: Two-pane List-Detail layout on expanded screens and System/Light/Dark theme settings persisted via Room.