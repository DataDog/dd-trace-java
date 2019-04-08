<?xml version="1.0" encoding="UTF-8"?>
<!--
  Copyright 2019 Datadog
 
  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at
 
      http://www.apache.org/licenses/LICENSE-2.0
 
  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->
<!-- Transformation for going from .jfc to .jfp file -->
<xsl:stylesheet
	xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="2.0">
	<xsl:output method="text" indent="yes" />
	<xsl:strip-space elements="*" />
	<xsl:template match="event">
		<xsl:for-each select="setting">
			<xsl:text><xsl:value-of select="../@name" /></xsl:text>
			<xsl:text>#</xsl:text>
			<xsl:value-of select="@name" />
			<xsl:text>=</xsl:text>
			<xsl:value-of select="." />
			<xsl:text>&#xa;</xsl:text>
		</xsl:for-each>
	</xsl:template>
</xsl:stylesheet>
