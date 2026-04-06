# Changelog

## [2.0.0] - 2026-04-06

### Added

- Maven project debugging (compile → dependency:build-classpath → java -cp)
- Gradle project debugging (classes → init script classpath → java -cp)
- Spring Boot project support
- Dynamic debug port allocation (no more port 5005 conflicts)
- stderr forwarding to DAP console
- Main class FQCN auto-detection from file path
- Main class persistence (.nvim-java-debugger/main_class)
- Main class selection popup when current file differs from saved record
- `<leader>dm` keymap to manually set main class
- install.sh for auto-downloading adapter JAR from GitHub Releases
- GitHub Actions workflow for automated release builds
- Git-based version numbering (git describe --tags)

### Fixed

- Thread-aware stepping and variable inspection (fixes multi-thread apps like Spring Boot)
- Single ClassPrepareRequest per class (fixes skipped first breakpoint)
- Synchronized DAP message sending (fixes JSON corruption in multi-thread output)
- JDI attach with retry (fixes JDWP handshake failures)
- Clean adapter exit on disconnect and program termination (no more exit code 130)
- Maven/Gradle command validation with clear error messages

## [1.0.0] - 2026-04-03

### Added

- DAP adapter backend implementing the Debug Adapter Protocol via JDI
- Single-file Java project debugging with incremental compilation
- Breakpoint support (set, toggle, deferred breakpoints for unloaded classes)
- Execution control: continue, step over, step into, step out, pause
- Variable inspection (local scope)
- Stack trace display with correct source path resolution
- stdout forwarding to DAP console
- Breakpoint persistence across Neovim sessions
- Debug mode keymaps (single-key shortcuts during debug session)
- which-key integration for keymap group display
- Customizable breakpoint signs (red dot for breakpoints, arrow for stopped line)
- Automatic project type detection (Maven, Gradle, single file)
- Compiled `.class` files stored in `.nvim-java-debugger/build/` to avoid polluting project directory
- `config.status()` API for statusline integration
- Example lazy.nvim plugin spec (`nvim-java-debugger.lua`)
