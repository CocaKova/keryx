package chat.keryx.core

import chat.keryx.core.model.ArtifactBase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArtifactBaseTest {
    @Test
    fun siblingsResolveInsideTheFolderOnly() {
        assertEquals("https://artifact.keryx.invalid/home/me/site/", ArtifactBase.baseUrl("/home/me/site/index.html"))
        assertNull(ArtifactBase.baseUrl("index.html"))
        val folder = ArtifactBase.folderOf("/home/me/site/index.html")!!
        val host = ArtifactBase.HOST
        assertEquals("/home/me/site/style.css", ArtifactBase.resolve(folder, host, "/home/me/site/style.css"))
        assertEquals("/home/me/site/img/a.png", ArtifactBase.resolve(folder, host, "/home/me/site/js/../img/a.png"))
        // Out of the folder, another host, or nothing at all: not answered.
        assertNull(ArtifactBase.resolve(folder, host, "/home/me/site/../secrets.txt"))
        assertNull(ArtifactBase.resolve(folder, host, "/etc/passwd"))
        assertNull(ArtifactBase.resolve(folder, "evil.example", "/home/me/site/style.css"))
        assertNull(ArtifactBase.resolve(folder, host, "/home/me/site"))
        assertEquals("text/css", ArtifactBase.mimeOf("a/b.CSS"))
        assertEquals("application/octet-stream", ArtifactBase.mimeOf("noext"))
    }
}
