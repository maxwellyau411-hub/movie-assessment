plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

dependencies {
    api(platform(libs.spring.boot.bom))
    api("io.grpc:grpc-protobuf")
    api("io.grpc:grpc-stub")
    api("com.google.protobuf:protobuf-java")
}

protobuf {
    protoc { artifact = libs.protoc.get().toString() }
    plugins {
        create("grpc") { artifact = libs.grpc.protoc.gen.get().toString() }
    }
    generateProtoTasks {
        all().configureEach {
            plugins { create("grpc") }
        }
    }
}
