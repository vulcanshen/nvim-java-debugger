local M = {}

M.opts = {
  adapter_jar = nil,
  keymaps = true,
  keymap_prefix = "<leader>d",
}

function M.setup(opts)
  M.opts = vim.tbl_deep_extend("force", M.opts, opts or {})
end

--- 回傳 debug 狀態文字，可用於 lualine 或其他 statusline
function M.status()
  if M._debug_mode_active then
    return "Debugging"
  end
  return ""
end

--- 從檔案路徑推算 FQCN
function M.filepath_to_fqcn(filepath)
  filepath = filepath or vim.fn.expand("%:p")
  local markers = { "src/main/java/", "src/test/java/", "src/" }
  for _, marker in ipairs(markers) do
    local idx = filepath:find(marker, 1, true)
    if idx then
      local relative = filepath:sub(idx + #marker)
      return relative:gsub("%.java$", ""):gsub("/", ".")
    end
  end
  -- fallback: 檔名（single file）
  return vim.fn.expand("%:t:r")
end

--- mainClass 記錄檔路徑
local function main_class_store_path()
  return vim.fn.getcwd() .. "/.vim-java-debugger/main_class"
end

--- 讀取上次的 mainClass
function M.load_main_class()
  local path = main_class_store_path()
  local f = io.open(path, "r")
  if not f then return nil end
  local content = f:read("*a")
  f:close()
  content = content:gsub("%s+", "")
  return content ~= "" and content or nil
end

--- 儲存 mainClass
function M.save_main_class(fqcn)
  local dir = vim.fn.getcwd() .. "/.vim-java-debugger"
  if vim.fn.isdirectory(dir) == 0 then
    vim.fn.mkdir(dir, "p")
  end
  local f = io.open(main_class_store_path(), "w")
  if f then
    f:write(fqcn)
    f:close()
  end
end

--- 決定 mainClass：上次記錄 > 當前檔案 FQCN
function M.resolve_main_class()
  local saved = M.load_main_class()
  if saved then
    return saved
  end
  local fqcn = M.filepath_to_fqcn()
  M.save_main_class(fqcn)
  return fqcn
end

--- 讓使用者手動重新選擇 mainClass
function M.select_main_class()
  local current_fqcn = M.filepath_to_fqcn()
  vim.ui.input({
    prompt = "Main class: ",
    default = current_fqcn,
  }, function(input)
    if input and input ~= "" then
      M.save_main_class(input)
      vim.notify("Main class set to: " .. input, vim.log.levels.INFO)
    end
  end)
end

function M.get_adapter_jar()
  if M.opts.adapter_jar then
    return M.opts.adapter_jar
  end

  local plugin_dir = vim.fn.fnamemodify(
    debug.getinfo(1, "S").source:sub(2), ":h:h:h"
  )
  local jar = plugin_dir .. "/adapter/build/libs/vim-java-debugger-0.1.0-all.jar"

  if vim.fn.filereadable(jar) == 0 then
    vim.notify(
      "vim-java-debugger: adapter jar not found. Run :VimJavaDebuggerBuild first.",
      vim.log.levels.ERROR
    )
    return nil
  end

  return jar
end

function M.setup_keymaps()
  local prefix = M.opts.keymap_prefix
  local map = vim.keymap.set

  -- 註冊 which-key group（如果有安裝）
  local wk_ok, wk = pcall(require, "which-key")
  if wk_ok then
    wk.add({ { prefix, group = "Debug" } })
  end

  map("n", prefix .. "b", function() require("dap").toggle_breakpoint() end, { desc = "Toggle breakpoint" })
  map("n", prefix .. "c", function() require("dap").continue() end, { desc = "Continue / Start" })
  map("n", prefix .. "n", function() require("dap").step_over() end, { desc = "Step over" })
  map("n", prefix .. "i", function() require("dap").step_into() end, { desc = "Step into" })
  map("n", prefix .. "o", function() require("dap").step_out() end, { desc = "Step out" })
  map("n", prefix .. "p", function() require("dap").pause() end, { desc = "Pause" })
  map("n", prefix .. "r", function() require("dap").repl.open() end, { desc = "Open REPL" })
  map("n", prefix .. "q", function() require("dap").terminate() end, { desc = "Terminate debug" })
  map("n", prefix .. "m", function() M.select_main_class() end, { desc = "Set main class" })

  -- Debug mode: debug session 啟動時綁定簡單按鍵，結束時還原
  M.setup_debug_mode_keymaps()
end

M._debug_mode_active = false
M._saved_keymaps = {}

function M.setup_debug_mode_keymaps()
  local dap = require("dap")

  local debug_keys = {
    { "n", function() dap.step_over() end, "Step over" },
    { "i", function() dap.step_into() end, "Step into" },
    { "o", function() dap.step_out() end, "Step out" },
    { "c", function() dap.continue() end, "Continue" },
    { "b", function() dap.toggle_breakpoint() end, "Toggle breakpoint" },
    { "p", function() dap.pause() end, "Pause" },
    { "q", function() dap.terminate() end, "Quit debug" },
  }

  local function save_and_set()
    if M._debug_mode_active then return end
    M._debug_mode_active = true
    M._saved_keymaps = {}

    for _, key in ipairs(debug_keys) do
      -- 儲存原本的 keymap
      local existing = vim.fn.maparg(key[1], "n", false, true)
      if existing and existing.lhs then
        table.insert(M._saved_keymaps, existing)
      else
        table.insert(M._saved_keymaps, { lhs = key[1], unmapped = true })
      end
      vim.keymap.set("n", key[1], key[2], { desc = "[Debug] " .. key[3], nowait = true })
    end

    vim.notify("Debug mode ON: n/i/o/c/b/p/q", vim.log.levels.INFO)

    -- 刷新 lualine 顯示 Debugging 狀態
    pcall(function() require("lualine").refresh() end)
  end

  local function restore()
    if not M._debug_mode_active then return end
    M._debug_mode_active = false

    for _, saved in ipairs(M._saved_keymaps) do
      if saved.unmapped then
        pcall(vim.keymap.del, "n", saved.lhs)
      else
        local rhs = saved.callback or saved.rhs
        if rhs then
          vim.keymap.set("n", saved.lhs, rhs, {
            silent = saved.silent == 1,
            noremap = saved.noremap == 1,
            desc = saved.desc,
          })
        end
      end
    end
    M._saved_keymaps = {}

    vim.notify("Debug mode OFF", vim.log.levels.INFO)

    -- 重新設定 breakpoint signs（session 結束後 signs 會變成 unverified）
    vim.defer_fn(function()
      local dap_bps = require("dap.breakpoints")
      local all_bps = dap_bps.get()
      for bufnr, buf_bps in pairs(all_bps) do
        -- 清除再重設，讓 sign 回到預設的 DapBreakpoint
        dap_bps.clear(bufnr)
        for _, bp in ipairs(buf_bps) do
          dap_bps.set({
            condition = bp.condition,
            hitCondition = bp.hitCondition,
            logMessage = bp.logMessage,
          }, bufnr, bp.line)
        end
      end
    end, 200)

    pcall(function() require("lualine").refresh() end)
  end

  dap.listeners.after.event_initialized["debug_mode"] = save_and_set
  dap.listeners.after.event_terminated["debug_mode"] = restore
  dap.listeners.after.event_exited["debug_mode"] = restore
  dap.listeners.after.disconnect["debug_mode"] = restore
end

return M
