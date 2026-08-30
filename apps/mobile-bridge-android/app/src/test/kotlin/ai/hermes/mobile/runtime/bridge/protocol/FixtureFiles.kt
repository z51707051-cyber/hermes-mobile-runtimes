package ai.hermes.mobile.runtime.bridge.protocol

import java.nio.file.Files
import java.nio.file.Path

internal object FixtureFiles {
    val repositoryRoot: Path =
        generateSequence(Path.of("").toAbsolutePath().normalize()) { current -> current.parent }
            .firstOrNull { candidate ->
                Files.isDirectory(candidate.resolve("tests/mobile/contract/fixtures/v0.1"))
            } ?: error("cannot locate repository root for protocol fixtures")
    val root: Path = repositoryRoot.resolve("tests/mobile/contract/fixtures/v0.1")
    val schemaRoot: Path = repositoryRoot.resolve("hermes_mobile/protocol/schemas/v0.1")

    fun bytes(relative: String): ByteArray = Files.readAllBytes(root.resolve(relative))
}
