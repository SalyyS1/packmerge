package com.salyvn.packmerge

import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.versionOption
import com.salyvn.packmerge.cli.MergeCommand
import com.salyvn.packmerge.core.BuildInfo

/**
 * Entry point. One command, because the tool does one thing: put several resource packs together the way the
 * client would, instead of the way `cp -r` does.
 */
fun main(args: Array<String>) = MergeCommand()
    .versionOption(BuildInfo.version, names = setOf("--version"))
    .main(args)
