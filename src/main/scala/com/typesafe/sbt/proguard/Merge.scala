package com.typesafe.sbt.proguard

import sbt._
import sbt.classpath.ClasspathUtilities
import java.io.File
import java.util.regex.Pattern
import scala.util.matching.Regex

object Merge {
  object EntryPath {
    val pattern = Pattern.compile(if (File.separator == "\\") "\\\\" else File.separator)
  }

  case class EntryPath(path: String, isDirectory: Boolean) {
    val list = EntryPath.pattern.split(path).toList
    val name = if (list.isEmpty) "" else list.last
    val normalised = list.mkString("/") + (if (isDirectory) "/" else "")
    override def toString = normalised
    def file(base: File) = base / path
  }

  object Entry {
    def apply(path: String, file: File, source: File): Entry =
      Entry(EntryPath(path, file.isDirectory), file, source)
  }

  case class Entry(path: EntryPath, file: File, source: File)

  def entries(sources: Seq[File], tmp: File): Seq[Entry] = {
    sources flatMap { source =>
      val base = if (ClasspathUtilities.isArchive(source)) {
        val unzipped = tmp / source.getCanonicalPath
        IO.unzip(source, unzipped)
        unzipped
      } else source
      (base.*** --- base).get x relativeTo(base) map { p => Entry(p._2, p._1, source) }
    }
  }

  trait Strategy {
    def claims(path: EntryPath): Boolean
    def merge(path: EntryPath, entries: Seq[Entry], target: File, log: Logger): Unit
  }

  object Strategy {
    val deduplicate = new Strategy {
      def claims(path: EntryPath): Boolean = true
      def merge(path: EntryPath, entries: Seq[Entry], target: File, log: Logger): Unit = {
        Merge.deduplicate(path, entries, target, log)
      }
    }

    def discard(exactly: String) = new Strategy {
      def claims(path: EntryPath): Boolean = path.normalised == exactly
      def merge(path: EntryPath, entries: Seq[Entry], target: File, log: Logger): Unit = {
        Merge.discard(entries, log)
      }
    }

    def discard(matching: Regex) = new Strategy {
      def claims(path: EntryPath): Boolean = matching.findFirstIn(path.normalised).isDefined
      def merge(path: EntryPath, entries: Seq[Entry], target: File, log: Logger): Unit = {
        Merge.discard(entries, log)
      }
    }
  }

  def merge(sources: Seq[File], target: File, strategies: Seq[Strategy], log: Logger): Unit = {
    IO.withTemporaryDirectory { tmp =>
      var failed = false
      val groupedEntries = entries(sources, tmp) groupBy (_.path)
      for ((path, entries) <- groupedEntries) {
        val strategy = strategies find { _.claims(path) } getOrElse Strategy.deduplicate
        try {
          strategy.merge(path, entries, target, log)
        } catch {
          case e: Exception =>
            log.error(e.getMessage)
            failed = true
        }
      }
      if (failed) {
        sys.error("Failed to merge all inputs. Merge strategies can be used to resolve conflicts.")
        IO.delete(target)
      }
    }
  }

  def deduplicate(path: EntryPath, entries: Seq[Entry], target: File, log: Logger): Unit = {
    if (entries.size > 1) {
      if (path.isDirectory) {
        log.debug("Ignoring duplicate directories at '%s'" format path)
        path.file(target).mkdirs
      } else {
        entries foreach { e => log.debug("Matching entry at '%s' from %s" format (e.path, e.source.name)) }
        val hashes = (entries map { _.file.hashString }).toSet
        if (hashes.size <= 1) {
          log.debug("Identical duplicates found at '%s'" format path)
          copyFirst(entries, target)
        } else {
          sys.error("Multiple entries found at '%s'" format path)
        }
      }
    } else {
      if (path.isDirectory) path.file(target).mkdirs
      else copyFirst(entries, target)
    }
  }

  def discard(entries: Seq[Entry], log: Logger): Unit = {
    entries foreach { e => log.debug("Discarding entry at '%s' from %s" format (e.path, e.source.name)) }
  }

  def copyFirst(entries: Seq[Entry], target: File): Unit = {
    for (entry <- entries.headOption) {
      IO.copyFile(entry.file, entry.path.file(target))
    }
  }
}