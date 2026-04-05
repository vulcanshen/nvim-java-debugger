# vim-java-debugger

A Neovim plugin for debugging Java applications using the [Debug Adapter Protocol (DAP)](https://microsoft.github.io/debug-adapter-protocol/).

## Features

- **Maven / Gradle / Single-file** Java project support
- Breakpoints with persistence across sessions
- Step over / into / out, continue, pause
- Variable inspection and stack traces
- stdout/stderr forwarding to DAP console
- Debug mode keymaps (`n`/`i`/`o`/`c`/`b`/`p`/`q` during debug session)
- which-key integration
- Incremental compilation (single-file projects)
- Customizable breakpoint signs

## Requirements

- **Neovim** >= 0.9
- **Java** >= 11
- [nvim-dap](https://github.com/mfussenegger/nvim-dap)
- (Optional) [nvim-dap-ui](https://github.com/rcarriga/nvim-dap-ui) for debug panels
- (Optional) [which-key.nvim](https://github.com/folke/which-key.nvim) for keymap hints
- **Maven** projects: `mvnw` or system `mvn`
- **Gradle** projects: `gradlew` or system `gradle`

## Installation

### lazy.nvim

```lua
return {
  {
    "mfussenegger/nvim-dap",
    lazy = true,
  },
  {
    "vulcanshen/vim-java-debugger",
    ft = "java",
    dependencies = { "mfussenegger/nvim-dap" },
    build = "./install.sh",
    config = function()
      require("vim-java-debugger").setup()
    end,
  },
}
```

The `install.sh` script automatically downloads the pre-built adapter JAR from GitHub Releases. No JDK or Gradle needed for installation.

### Build from source (for development)

```bash
cd adapter
./gradlew build
```

## Configuration

```lua
require("vim-java-debugger").setup({
  adapter_jar = nil,            -- auto-detected from plugin directory
  keymaps = true,               -- enable default keymaps
  keymap_prefix = "<leader>d",  -- prefix for keymaps
})
```

## Keymaps

### Default keymaps (prefix: `<leader>d`)

| Key            | Action              |
|----------------|---------------------|
| `<leader>db`   | Toggle breakpoint   |
| `<leader>dc`   | Continue / Start    |
| `<leader>dn`   | Step over           |
| `<leader>di`   | Step into           |
| `<leader>do`   | Step out            |
| `<leader>dp`   | Pause               |
| `<leader>dr`   | Open REPL           |
| `<leader>dq`   | Terminate debug     |
| `<leader>dm`   | Set main class      |

### Debug mode

When a debug session starts, simplified single-key keymaps are activated automatically:

| Key | Action              |
|-----|---------------------|
| `n` | Step over           |
| `i` | Step into           |
| `o` | Step out            |
| `c` | Continue            |
| `b` | Toggle breakpoint   |
| `p` | Pause               |
| `q` | Quit debug          |

Original keymaps are restored when the session ends.

## Statusline Integration

The plugin exposes a `status()` function for statusline integration:

```lua
-- Example with lualine
{
  function()
    return require("vim-java-debugger.config").status()
  end,
  color = { fg = "#f38ba8", gui = "bold" },
}
```

Returns `"Debugging"` when a debug session is active, empty string otherwise.

## Supported Project Types

### Single-file Java
Open a `.java` file with a `main` method and press `<leader>dc`. The plugin compiles and runs it directly.

### Maven
The plugin runs `mvn compile`, resolves the classpath via `mvn dependency:build-classpath`, and launches with `java -cp`. Requires `mvnw` (Maven Wrapper) in the project or system `mvn`.

### Gradle
The plugin runs `gradle classes`, resolves the classpath via a temporary init script, and launches with `java -cp`. Requires `gradlew` (Gradle Wrapper) in the project or system `gradle`. Works with any Gradle project including Spring Boot.

## Main Class Resolution

When you press `<leader>dc`, the plugin determines which class to launch:

1. **No saved record** — uses the current file's fully qualified class name (must have a `main` method, otherwise shows an error)
2. **Has saved record, current file is the same** — uses it directly
3. **Has saved record, current file is different and has `main`** — shows a selection popup (saved vs current). Your choice does **not** overwrite the saved record
4. **Has saved record, current file has no `main`** — uses the saved record

The main class is saved to `.vim-java-debugger/main_class` after a successful debug launch. Use `<leader>dm` to manually set or change it.

## How It Works

1. The plugin registers a DAP adapter that launches a Java-based debug server
2. The server communicates with Neovim via the DAP protocol over stdin/stdout
3. The server uses Java Debug Interface (JDI) to connect to and control the target JVM
4. For Maven/Gradle projects, the plugin compiles and resolves classpath automatically
5. For single-file projects, the source is compiled with incremental builds
6. Breakpoints are persisted to `.vim-java-debugger/breakpoints.json` in the project directory

## Project Structure

```
vim-java-debugger/
├── adapter/              # Java DAP adapter (Gradle project)
│   └── src/main/java/    # DapServer, JavaDebugger, etc.
├── lua/vim-java-debugger/ # Neovim plugin (Lua)
│   ├── init.lua           # Plugin setup & DAP registration
│   ├── config.lua         # Configuration & keymaps
│   └── breakpoints.lua    # Breakpoint persistence
└── vim-java-debugger.lua  # Example lazy.nvim plugin spec
```

## License

GPL-3.0
