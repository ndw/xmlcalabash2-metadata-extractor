package com.xmlcalabash.ext

import java.net.URI

import com.xmlcalabash.config.XMLCalabashConfig
import com.xmlcalabash.runtime.{PrintingConsumer, XMLCalabashRuntime}
import net.sf.saxon.s9api.QName

object Main extends App {
  val config = XMLCalabashConfig.newInstance()
  val runtime: XMLCalabashRuntime = config.runtime(new URI(args.head))
  val serOpt = runtime.serializationOptions("result")
  val pc = new PrintingConsumer(runtime, serOpt)
  runtime.output("result", pc)
  runtime.run()
}
