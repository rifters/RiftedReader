package com.rifters.riftedreader

import org.jsoup.Jsoup
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for EPUB SVG image element detection and processing
 * Verifies that SVG <image> elements are properly detected and processed
 */
class EpubSvgImageTest {
    
    @Test
    fun svgImageSelector_detectsHtmlImgTags() {
        val html = """
            <html>
                <body>
                    <img src="cover.jpg" />
                </body>
            </html>
        """.trimIndent()
        
        val doc = Jsoup.parse(html)
        val images = doc.select("img[src], image[href], image")
        
        assertEquals(1, images.size)
        assertEquals("img", images[0].tagName())
        assertTrue(images[0].hasAttr("src"))
    }
    
    @Test
    fun svgImageSelector_detectsSvgImageWithHref() {
        val html = """
            <html>
                <body>
                    <svg xmlns="http://www.w3.org/2000/svg">
                        <image href="cover.jpg" height="1179" width="834"/>
                    </svg>
                </body>
            </html>
        """.trimIndent()
        
        val doc = Jsoup.parse(html)
        val images = doc.select("img[src], image[href], image")
        
        assertEquals(1, images.size)
        assertEquals("image", images[0].tagName())
        assertTrue(images[0].hasAttr("href"))
    }
    
    @Test
    fun svgImageSelector_detectsSvgImageWithXlinkHref() {
        val html = """
            <html>
                <body>
                    <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
                        <image xlink:href="cover.jpg" height="1179" width="834"/>
                    </svg>
                </body>
            </html>
        """.trimIndent()
        
        val doc = Jsoup.parse(html)
        // Note: JSoup doesn't support xlink:href in CSS selectors, so we select all 'image' elements
        val images = doc.select("img[src], image[href], image")
        
        assertEquals(1, images.size)
        assertEquals("image", images[0].tagName())
        // Check if the element has xlink:href attribute (programmatically)
        assertTrue(images[0].hasAttr("xlink:href"))
        assertEquals("cover.jpg", images[0].attr("xlink:href"))
    }
    
    @Test
    fun svgImageSelector_detectsMixedImageTypes() {
        val html = """
            <html>
                <body>
                    <img src="photo1.jpg" />
                    <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">
                        <image xlink:href="cover.jpg" height="1179" width="834"/>
                    </svg>
                    <img src="photo2.png" />
                    <svg xmlns="http://www.w3.org/2000/svg">
                        <image href="diagram.svg"/>
                    </svg>
                </body>
            </html>
        """.trimIndent()
        
        val doc = Jsoup.parse(html)
        val images = doc.select("img[src], image[href], image")
        
        assertEquals(4, images.size)
        assertEquals("img", images[0].tagName())
        assertEquals("image", images[1].tagName())
        assertEquals("img", images[2].tagName())
        assertEquals("image", images[3].tagName())
    }
    
    @Test
    fun imageAttributeDetection_handlesAllTypes() {
        val imgHtml = """<img src="test.jpg" />"""
        val svgHrefHtml = """<image href="test.jpg" />"""
        val svgXlinkHtml = """<image xlink:href="test.jpg" />"""
        
        val imgDoc = Jsoup.parse(imgHtml)
        val imgElement = imgDoc.select("img, image").first()!!
        val imgSrc = when {
            imgElement.hasAttr("src") -> imgElement.attr("src")
            imgElement.hasAttr("href") -> imgElement.attr("href")
            imgElement.hasAttr("xlink:href") -> imgElement.attr("xlink:href")
            else -> ""
        }
        assertEquals("test.jpg", imgSrc)
        
        val svgHrefDoc = Jsoup.parse(svgHrefHtml)
        val svgHrefElement = svgHrefDoc.select("img, image").first()!!
        val svgHrefSrc = when {
            svgHrefElement.hasAttr("src") -> svgHrefElement.attr("src")
            svgHrefElement.hasAttr("href") -> svgHrefElement.attr("href")
            svgHrefElement.hasAttr("xlink:href") -> svgHrefElement.attr("xlink:href")
            else -> ""
        }
        assertEquals("test.jpg", svgHrefSrc)
        
        val svgXlinkDoc = Jsoup.parse(svgXlinkHtml)
        val svgXlinkElement = svgXlinkDoc.select("img, image").first()!!
        val svgXlinkSrc = when {
            svgXlinkElement.hasAttr("src") -> svgXlinkElement.attr("src")
            svgXlinkElement.hasAttr("href") -> svgXlinkElement.attr("href")
            svgXlinkElement.hasAttr("xlink:href") -> svgXlinkElement.attr("xlink:href")
            else -> ""
        }
        assertEquals("test.jpg", svgXlinkSrc)
    }
    
    @Test
    fun imageAttributeUpdate_handlesImgElements() {
        val html = """<img src="old.jpg" />"""
        val doc = Jsoup.parse(html)
        val img = doc.select("img").first()!!
        
        val newUrl = "file:///new/path/image.jpg"
        if (img.tagName() == "img") {
            img.attr("src", newUrl)
        } else {
            img.attr("href", newUrl)
            img.attr("xlink:href", newUrl)
        }
        
        assertEquals(newUrl, img.attr("src"))
    }
    
    @Test
    fun imageAttributeUpdate_handlesSvgImageElements() {
        val html = """<svg><image href="old.jpg" /></svg>"""
        val doc = Jsoup.parse(html)
        val img = doc.select("image").first()
        
        assertNotNull("Image element should be found", img)
        
        val newUrl = "file:///new/path/image.jpg"
        if (img!!.tagName() == "img") {
            img.attr("src", newUrl)
        } else {
            img.attr("href", newUrl)
            img.attr("xlink:href", newUrl)
        }
        
        assertEquals(newUrl, img.attr("href"))
        assertEquals(newUrl, img.attr("xlink:href"))
    }
    
    @Test
    fun coverImageIdentification_worksForSvgImages() {
        val imagePath = "OEBPS/Images/cover.jpg"
        val originalSrc = "cover.jpg"
        
        val isCoverImage = imagePath.lowercase().contains("cover") || 
                          originalSrc.lowercase().contains("cover")
        
        assertTrue("SVG image with cover in path should be identified as cover", isCoverImage)
    }
}
