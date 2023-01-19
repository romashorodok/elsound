'use strict';

const path = require("path");

const MiniCssExtractPlugin = require("mini-css-extract-plugin");

const root = path.resolve(__dirname, "./");
const resources = path.join(root, "resources");
const publicDir = path.join(resources, "public");
const styles = path.join(resources, "styles");

const styleCSS = styles.concat("/style.css");

const cssLoader = {
  test: /\.css$|.s[ac]ss$/i,
  use: [
    MiniCssExtractPlugin.loader,
    "css-loader",
    "postcss-loader",
    "sass-loader"
  ]
};

const { Compilation } = require("webpack");

class JsIgnorePlugin {
  apply(compiler) {
    compiler.hooks.compilation.tap("Disable js compilation", (compilation) => {
      compilation.hooks.processAssets.tap({
        name: "Disable",
        stage: Compilation.PROCESS_ASSETS_STAGE_ANALYSE
      }, () => {
        for (const [filename] of Object.entries(compilation.assets))
          if (filename.match(/.*\.js$/))
            compilation.deleteAsset(filename)
      })
    })
  }
}

module.exports = {
  mode: "development",

  module: {
    rules: [
      cssLoader
    ]
  },
  
  plugins: [
    new MiniCssExtractPlugin({
      filename: "core.mini.css"
    }),
    new JsIgnorePlugin()
  ],
  
  entry: {
    core: [
      styleCSS
    ]
  },

  output: {
    path: publicDir,
  }
}
