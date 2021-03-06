/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.abhioncbr.daflow.job.conf.xml

import com.abhioncbr.daflow.commons.CommonSpec
import scala.xml.XML

class XmlJobConfBase extends CommonSpec{

  val userDirectory: String = System.getProperty("user.dir")

  val daflowExamplesPath = s"$userDirectory/daflow-examples"
  val daflowExamplesDemoPath = s"$daflowExamplesPath/demo"
  val daflowExamplesDemoSampleDataPath = s"$daflowExamplesDemoPath/sample-data"
  val daflowExampleDemoJobXmlPath = s"$daflowExamplesDemoPath/daflow-job-xml"
  val daflowExampleJobXmlTemplatePath = s"$daflowExamplesPath/daflow-xml-templates"

  val xsdFilePath = s"$userDirectory/daflow-job-conf/daflow-job-conf-xml/daflow-feed-job.xsd"

  val node: String => scala.xml.NodeSeq = (xmlContent: String) => { XML.loadString(xmlContent) }

}
