package com.xmlcalabash.ext

import java.io.InputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.TimeZone

import com.drew.imaging.ImageMetadataReader
import com.jafpl.runtime.RuntimeConfiguration
import com.xmlcalabash.model.util.{SaxonTreeBuilder, XProcConstants}
import com.xmlcalabash.runtime.{ImplParams, StaticContext, XProcMetadata, XmlPortSpecification}
import com.xmlcalabash.sbt.metadata_extractor.BuildInfo
import com.xmlcalabash.steps.DefaultXmlStep
import com.xmlcalabash.util.MediaType
import net.sf.saxon.s9api.QName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.xmpbox.XMPMetadata
import org.apache.xmpbox.`type`.{ArrayProperty, Cardinality, DateType, MIMEType, TextType}
import org.apache.xmpbox.xml.DomXmpParser

import scala.collection.JavaConverters._
import scala.collection.mutable

class MetadataExtractor extends DefaultXmlStep {
  private val PDF = new MediaType("text", "html")
  private val df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'")
  private val _href = new QName("", "href")
  private val c_metadata = new QName("c", XProcConstants.ns_c, "metadata")
  private val c_tag = new QName("c", XProcConstants.ns_c, "tag")
  private val _dir = new QName("", "dir")
  private val _type = new QName("", "type")
  private val _name = new QName("", "name")

  private val controls = Array[String]("0000",
            "0001", "0002", "0003", "0004", "0005", "0006", "0007",
    "0008",                 "000b", "000c",         "000e", "000f",
    "0010", "0011", "0012", "0013", "0014", "0015", "0016", "0017",
    "0018", "0019", "001a", "001b", "001c", "001d", "001e", "001f",
    "007c")

  private val library_xpl = "http://xmlcalabash.com/extension/steps/metadata-extractor.xpl"
  private val library_url = "/com/xmlcalabash/extensions/metadata-extractor/library.xpl"
  private var stream: Option[InputStream] = None
  private var baseURI: Option[URI] = None
  private var contentType: Option[MediaType] = None

  df.setTimeZone(TimeZone.getTimeZone("UTC"))

  override def inputSpec: XmlPortSpecification = XmlPortSpecification.ANYSOURCE
  override def outputSpec: XmlPortSpecification = XmlPortSpecification.XMLRESULT

  override def initialize(config: RuntimeConfiguration, params: Option[ImplParams]): Unit = {
    super.initialize(config, params)
    if (config.traceEnabled("verbose-init")) {
      logger.info(s"${BuildInfo.stepName} version ${BuildInfo.version} for XML Calabash ${BuildInfo.xmlCalabashVersion}")
      logger.info(s"[${BuildInfo.name}/${BuildInfo.gitHash.substring(0,8)}, ${BuildInfo.vendor}, ${BuildInfo.vendorUri}]")
    }
  }

  override def receive(port: String, item: Any, metadata: XProcMetadata): Unit = {
    item match {
      case s: InputStream =>
        stream = Some(s)
        if (metadata.properties.contains(XProcConstants._base_uri)) {
          baseURI = Some(new URI(metadata.properties(XProcConstants._base_uri).toString))
        }
        if (metadata.properties.contains(XProcConstants._content_type)) {
          contentType = Some(MediaType.parse(metadata.properties(XProcConstants._content_type).toString))
        }
      case _ => throw new RuntimeException("Expected stream")
    }
  }

  override def run(context: StaticContext): Unit = {
    if (stream.isEmpty) {
      throw new RuntimeException("No input stream")
    }

    if (contentType.isDefined && contentType.get.mediaType == "application" && contentType.get.mediaSubtype == "pdf") {
      pdfExtract(stream.get)
    } else {
      imageExtract(stream.get)
    }
  }

  private def imageExtract(stream: InputStream): Unit = {
    val metadata = ImageMetadataReader.readMetadata(stream)
    val tree = new SaxonTreeBuilder(config)
    tree.startDocument(baseURI)
    tree.addStartElement(c_metadata)
    tree.addAttribute(XProcConstants._content_type, contentType.getOrElse(MediaType.OCTET_STREAM).toString)
    if (baseURI.isDefined) {
      tree.addAttribute(_href, baseURI.get.toASCIIString)
    }
    tree.startContent()

    for (directory <- metadata.getDirectories.asScala) {
      val dir = directory.getName
      for (tag <- directory.getTags.asScala) {
        tree.addStartElement(c_tag)
        tree.addAttribute(_dir, dir)
        tree.addAttribute(_type, tag.getTagTypeHex)
        tree.addAttribute(_name, tag.getTagName)
        tree.startContent()

        if (tag.getDescription != null) {
          // Laboriously escape all the control characters with \\uxxxx, but first replace
          // \\uxxxx with \\u005cuxxxx so we don't inadvertantly change the meaning of a string
          var value = tag.getDescription.replaceAll("\\\\u([0-9a-fA-F]{4}+)", "\\\\u005cu$1")
          for (control <- controls) {
            val cmatch = "^.*\\\\u" + control + ".*$"
            if (value.matches(cmatch)) {
              value = value.replaceAll("[\\\\u" + control + "]", "\\\\u" + control)
            }
          }

          // Bah humbug...I don't see an easy way to tell if it's a date/time
          if (value.matches("^\\d\\d\\d\\d:\\d\\d:\\d\\d \\d\\d:\\d\\d:\\d\\d$")) {
            val iso = value.substring(0, 4) + "-" + value.substring(5, 7) + "-" + value.substring(8, 10) + "T" + value.substring(11, 19)
            value = iso
          }

          tree.addText(value)
        }
        tree.addEndElement()
        tree.addText("\n")
      }
    }

    tree.addEndElement()
    tree.endDocument()
    consumer.get.receive("result", tree.result, XProcMetadata.XML)
  }

  private def pdfExtract(stream: InputStream): Unit = {
    // This is *absurdly* incomplete

    val _pages = new QName("", "pages")
    val _width = new QName("", "width")
    val _height = new QName("", "height")
    val _units = new QName("", "units")

    val document = PDDocument.load(stream)
    val firstPage = if (document.getNumberOfPages > 0) {
      Some(document.getPage(0))
    } else {
      None
    }

    val pfxMap = mutable.HashMap.empty[String,String]
    val nsMap = mutable.HashMap.empty[String,String]
    var metadata = Option.empty[XMPMetadata]

    val catalog = document.getDocumentCatalog
    val meta = Option(catalog.getMetadata)
    if (meta.isDefined) {
      val xmpParser = new DomXmpParser()
      metadata = Some(xmpParser.parse(meta.get.createInputStream()))
      for (schema <- metadata.get.getAllSchemas.asScala) {
        for (prop <- schema.getAllProperties.asScala) {
          val prefix = computePrefix(prop.getPrefix, prop.getNamespace, pfxMap, nsMap)
          pfxMap += (prefix -> prop.getNamespace)
          nsMap += (prop.getNamespace -> prefix)
        }
      }
    }

    val tree = new SaxonTreeBuilder(config)
    tree.startDocument(baseURI)
    tree.addStartElement(c_metadata)

    for (pfx <- pfxMap.keySet) {
      tree.addNamespace(pfx, pfxMap(pfx))
    }

    tree.addAttribute(XProcConstants._content_type, contentType.getOrElse(PDF).toString)
    if (baseURI.isDefined) {
      tree.addAttribute(_href, baseURI.get.toASCIIString)
    }
    tree.addAttribute(_pages, document.getNumberOfPages.toString)

    if (firstPage.isDefined) {
      tree.addAttribute(_height, firstPage.get.getMediaBox.getHeight.toString)
      tree.addAttribute(_width, firstPage.get.getMediaBox.getWidth.toString)
      tree.addAttribute(_units, "pt")
    }

    tree.startContent()

    if (meta.isDefined) {
      val xmpParser = new DomXmpParser()
      val metadata = xmpParser.parse(meta.get.createInputStream())
      for (schema <- metadata.getAllSchemas.asScala) {
        for (prop <- schema.getAllProperties.asScala) {
          val pfx = nsMap(prop.getNamespace)
          tree.addStartElement(new QName(pfx, prop.getNamespace, prop.getPropertyName))
          tree.startContent()

          prop match {
            case p: ArrayProperty =>
              val outer = if (p.getArrayType == Cardinality.Alt) {
                new QName("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Alt")
              } else {
                new QName("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#", "Seq")
              }
              val inner = new QName("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#", "li")

              tree.addStartElement(outer)
              tree.startContent()
              for (value <- p.getElementsAsString.asScala) {
                tree.addStartElement(inner)
                tree.startContent()
                tree.addText(value)
                tree.addEndElement()
              }
              tree.addEndElement()
            case p: MIMEType =>
              tree.addText(p.getStringValue)
            case p: TextType =>
              tree.addText(p.getStringValue)
            case p: DateType =>
              val cal = p.getValue
              tree.addText(df.format(cal.getTime))
            case _ =>
              println(s"cx:metadata-extractor: unknown property type: $prop")
              tree.addText(prop.toString)
          }

          tree.addEndElement()
        }
      }
    }

    tree.addEndElement()
    tree.endDocument()
    consumer.get.receive("result", tree.result, XProcMetadata.XML)
  }

  private def computePrefix(pfx: String, ns: String, pfxMap: mutable.HashMap[String,String], nsMap: mutable.HashMap[String,String]): String = {
    if (nsMap.contains(ns)) {
      nsMap(ns)
    } else if (!pfxMap.contains(pfx)) {
      pfx
    } else {
      val cpfx = "ns_"
      var count = 1
      while (pfxMap.get(s"$cpfx$count").isDefined) {
        count += 1
      }
      s"$cpfx$count"
    }
  }
}
