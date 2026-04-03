return {
  {
    "mfussenegger/nvim-dap",
    lazy = true,
  },
  {
    dir = "~/Documents/sideproj/vim-java-debugger",
    ft = "java",
    dependencies = { "mfussenegger/nvim-dap" },
    config = function()
      require("vim-java-debugger").setup()
    end,
  },
}
