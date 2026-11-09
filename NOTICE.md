# Third-party software in the APK

The app code in this repository is MIT. The APK also contains, unmodified:

- **Lightpanda** 1.0.3, aarch64 Linux build, AGPL-3.0. Source: https://github.com/lightpanda-io/browser
  (tag 1.0.3). Not affiliated with or endorsed by the Lightpanda project.
- **GNU C Library** (glibc), loader and libraries taken from Termux's glibc packages
  (https://github.com/termux-pacman/glibc-packages), LGPL-2.1. Source:
  https://sourceware.org/git/glibc.git and the Termux package recipes above.

`scripts/fetch-runtime.sh` shows exactly where each file comes from.
