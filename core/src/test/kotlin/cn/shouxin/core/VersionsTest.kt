package cn.shouxin.core
import org.junit.Assert.*
import org.junit.Test
class VersionsTest {
    @Test fun numericVersionOrdering() { assertTrue(Versions.newer("v0.10.0", "0.9.9")); assertFalse(Versions.newer("v0.1.0", "0.1.0")) }
    @Test fun rejectsPrereleasesAndMalformedVersions() { assertNull(Versions.parse("v1.2.3-beta")); assertNull(Versions.parse("1.2")); assertNull(Versions.parse("1.2.999999999999999999")) }
    @Test fun repositoryCannotBeUrlOrPathTraversal() { assertTrue(Versions.validRepository("zhouwenp925/come_on_APP")); assertFalse(Versions.validRepository("https://evil.invalid/repo")); assertFalse(Versions.validRepository("../repo")) }
}
