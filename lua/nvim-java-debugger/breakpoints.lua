local M = {}

local function get_store_path()
  return vim.fn.getcwd() .. "/.nvim-java-debugger/breakpoints.json"
end

local function ensure_dir()
  local dir = vim.fn.getcwd() .. "/.nvim-java-debugger"
  if vim.fn.isdirectory(dir) == 0 then
    vim.fn.mkdir(dir, "p")
  end
end

local function read_store()
  local path = get_store_path()
  local f = io.open(path, "r")
  if not f then return nil end
  local content = f:read("*a")
  f:close()
  if content == "" then return nil end
  local ok, data = pcall(vim.json.decode, content)
  if ok and type(data) == "table" then return data end
  return nil
end

--- 儲存所有 breakpoints 到檔案
function M.save()
  local ok, err = pcall(function()
    local dap_bps = require("dap.breakpoints")
    local bps = dap_bps.get()
    local data = {}
    local has_data = false

    for bufnr, buf_bps in pairs(bps) do
      local name = vim.api.nvim_buf_get_name(bufnr)
      if name ~= "" then
        local entries = {}
        for _, bp in ipairs(buf_bps) do
          local entry = { line = bp.line }
          if bp.condition then entry.condition = bp.condition end
          if bp.logMessage then entry.log_message = bp.logMessage end
          if bp.hitCondition then entry.hit_condition = bp.hitCondition end
          table.insert(entries, entry)
        end
        if #entries > 0 then
          data[name] = entries
          has_data = true
        end
      end
    end

    ensure_dir()
    local path = get_store_path()
    local json = has_data and vim.json.encode(data) or "{}"
    local f = io.open(path, "w")
    if f then
      f:write(json)
      f:close()
    end
  end)

  if not ok then
    vim.notify("nvim-java-debugger: failed to save breakpoints: " .. tostring(err), vim.log.levels.WARN)
  end
end

--- 針對指定 buffer 還原 breakpoints
function M.load_buf(bufnr)
  local ok, _ = pcall(function()
    local data = read_store()
    if not data then return end

    local dap_bps = require("dap.breakpoints")
    local bufname = vim.api.nvim_buf_get_name(bufnr)
    local bps = data[bufname]
    if not bps or type(bps) ~= "table" then return end

    for _, bp in ipairs(bps) do
      dap_bps.set({
        condition = bp.condition,
        hitCondition = bp.hit_condition,
        logMessage = bp.log_message,
      }, bufnr, bp.line)
    end
  end)
end

function M.setup()
  local dap = require("dap")

  -- 還原已開啟的 Java buffer 的 breakpoints
  for _, bufnr in ipairs(vim.api.nvim_list_bufs()) do
    local name = vim.api.nvim_buf_get_name(bufnr)
    if name:match("%.java$") and vim.api.nvim_buf_is_loaded(bufnr) then
      M.load_buf(bufnr)
    end
  end

  -- 之後開啟的 Java 檔案也還原
  vim.api.nvim_create_autocmd("BufReadPost", {
    pattern = "*.java",
    callback = function(args)
      vim.defer_fn(function()
        M.load_buf(args.buf)
      end, 100)
    end,
  })

  -- 攔截 breakpoint 操作，變更後自動儲存
  local orig_toggle = dap.toggle_breakpoint
  dap.toggle_breakpoint = function(...)
    orig_toggle(...)
    vim.schedule(function() M.save() end)
  end

  local orig_set_bp = dap.set_breakpoint
  dap.set_breakpoint = function(...)
    orig_set_bp(...)
    vim.schedule(function() M.save() end)
  end

  local orig_clear = dap.clear_breakpoints
  dap.clear_breakpoints = function(...)
    orig_clear(...)
    vim.schedule(function() M.save() end)
  end
end

return M
