<xsl:stylesheet
	xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="2.0">
	<xsl:output method="text" indent="yes" />
	<xsl:strip-space elements="*"/>
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
