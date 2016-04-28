package com.lihaoyi.workbench

import upickle.Js
import upickle.default.{Reader, Writer, readJs, writeJs}

/**
 * A standard way to read and write `Js.Value`s with autowire/upickle
 */
trait ReadWrite{
  def write[Result: Writer](r: Result): Js.Value =
    writeJs(r)
  def read[Result: Reader](p: Js.Value): Result =
    readJs[Result](p)
}

/**
 * Shared API between the workbench server and the workbench client,
 * comprising methods the server can call on the client to make it do
 * things
 */
trait Api{
  /**
   * Reset the HTML page to its initial state
   */
  def clear(): Unit
  /**
   * Reload the entire webpage
   */
  def reload(): Unit

  /**
   * Print a `msg` with the given logging `level`
   */
  def print(level: String, msg: String): Unit

  /**
   * Execute the javascript file available at the given `path`. Optionally,
   * run a `bootSnippet` after the file has been executed.
   */
  def run(path: String, bootSnippet: Option[String]): Unit
}