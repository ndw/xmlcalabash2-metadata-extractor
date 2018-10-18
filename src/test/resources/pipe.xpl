<p:declare-step version="3.0"
                xmlns:p="http://www.w3.org/ns/xproc"
                xmlns:cx="http://xmlcalabash.com/ns/extensions"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                exclude-inline-prefixes="cx xs"
                name="main">
  <p:output port="result" sequence='true'
            serialization="map { 'omit-xml-declaration': true(),
                                 'indent': true() }"/>
  <p:input port="source">
    <p:document href="/Users/ndw/Downloads/xmlss-deskpot.pdf"/>
  </p:input>

  <p:declare-step type="cx:metadata-extractor">
    <p:input port="source" content-types="image/*"/>
    <p:output port="result" content-types="application/xml"/>
  </p:declare-step>

  <cx:metadata-extractor/>

</p:declare-step>
