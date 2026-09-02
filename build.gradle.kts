plugins {
    id("java-library")
    id("xyz.jpenilla.run-velocity") version "3.1.0"
}

repositories {
    mavenCentral()
    mavenLocal()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven { url = uri("https://repo.codemc.io/repository/maven-releases/") }
    maven { url = uri("https://repo.codemc.io/repository/maven-snapshots/") }
    maven {
        name = "elytrium-repo"
        setUrl("https://maven.elytrium.net/repo/")
    }
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.5.1")
    compileOnly("net.elytrium.limboapi:api:1.1.27-SNAPSHOT")
    compileOnly("com.github.retrooper:packetevents-velocity:2.13.0")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks {
    runVelocity {
        // Configure the Velocity version for our task.
        // This is the only required configuration besides applying the plugin.
        // Your plugin's jar (or shadowJar if present) will be used automatically.
        velocityVersion("3.1.1")
    }

    processResources {
        val props = mapOf("version" to version)
        filesMatching("velocity-plugin.json") {
            expand(props)
        }
    }
}
