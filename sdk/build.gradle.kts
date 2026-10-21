plugins {
    `java-library`
}

dependencies {
    compileOnlyApi(libs.annotations)
    testCompileOnly(libs.annotations)

    // TOMLJ exposes Checker Framework annotations but declares them as runtime dependencies.
    compileOnly(libs.checker.qual)
    testCompileOnly(libs.checker.qual)

    api(libs.slf4j.api)

    implementation(libs.jackson.databind)
    implementation(libs.jgit)
    implementation(libs.kala.compress.tar)
    implementation(libs.kala.compress.bzip2)
    implementation(libs.kala.compress.lz4)
    implementation(libs.kala.compress.xz)
    implementation(libs.kala.compress.zstandard)
    implementation(libs.tomlj)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testRuntimeOnly(libs.slf4j.jdk14)
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
