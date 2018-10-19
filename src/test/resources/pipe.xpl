<p:declare-step version="3.0"
                xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:cx="http://xmlcalabash.com/ns/extensions"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                exclude-inline-prefixes="cx xs"
                name="main">
  <p:output port="result" sequence='true'
            serialization="map { 'omit-xml-declaration': true(),
                                 'indent': true() }"/>
  <p:option name="document" required="true"/>

  <p:declare-step type="cx:metadata-extractor">
    <p:input port="source" content-types="image/*"/>
    <p:output port="result" content-types="application/xml"/>
  </p:declare-step>

  <p:load href="{$document}"/>

  <cx:metadata-extractor/>

</p:declare-step>
