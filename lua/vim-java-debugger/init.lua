local M = {}

local config = require("vim-java-debugger.config")

function M.setup(opts)
  config.setup(opts)

  local ok, dap = pcall(require, "dap")
  if not ok then
    vim.notify("vim-java-debugger: nvim-dap is required", vim.log.levels.ERROR)
    return
  end

  dap.adapters.java_debugger = {
    type = "executable",
    command = "java",
    args = { "-jar", config.get_adapter_jar() },
  }

  dap.configurations.java = {
    {
      type = "java_debugger",
      request = "launch",
      name = "Debug Java",
      projectRoot = function()
        return vim.fn.getcwd()
      end,
      mainClass = function()
        -- 從當前檔案名推斷 class name
        local file = vim.fn.expand("%:t:r")
        return file
      end,
    },
  }

  -- 設定 DAP signs
  vim.fn.sign_define("DapBreakpoint", { text = "●", texthl = "DiagnosticError", linehl = "", numhl = "" })
  vim.fn.sign_define("DapBreakpointCondition", { text = "◆", texthl = "DiagnosticWarn", linehl = "", numhl = "" })
  vim.fn.sign_define("DapBreakpointRejected", { text = "○", texthl = "DiagnosticHint", linehl = "", numhl = "" })
  vim.fn.sign_define("DapStopped", { text = "→", texthl = "DiagnosticOk", linehl = "DapStoppedLine", numhl = "" })
  vim.fn.sign_define("DapLogPoint", { text = "◇", texthl = "DiagnosticInfo", linehl = "", numhl = "" })

  if config.opts.keymaps then
    config.setup_keymaps()
  end

  -- Breakpoint 持久化
  local bp = require("vim-java-debugger.breakpoints")
  bp.setup()
end

return M
