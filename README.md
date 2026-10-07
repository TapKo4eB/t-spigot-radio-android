<img width="192" height="192" alt="ic_launcher_round" src="https://github.com/user-attachments/assets/0b9d1d89-a9cd-426c-b11e-0f0b90f26389" />

imagine an app, and you can listen MUSIC in it.<br>
of course its just a single internet radiostation, but who cares?

# give me download, NOW!
can't do. still in pre-release. but you can go grab an artifact from latest commit ci if you are smart enough

# how 2 building
download android studio, pull project, build.

# screen state and orientation
Activity-scoped ViewModels own player, chat, history, bookmarks, and settings state.
The media controller and chat socket survive activity recreation and are released
when their ViewModels are cleared. Network polling runs in `viewModelScope`;
history polling pauses when its activity stops. Composables receive state and
callbacks and keep animation, menus, and scrolling local.

All activities request portrait orientation. Android 17 (the app targets API 37)
ignores orientation restrictions on large screens of at least 600dp, so a
portrait-only layout cannot be enforced there by the manifest.
See [Android's orientation restrictions](https://developer.android.com/about/versions/17/behavior-changes-17#large-screens).
