plugins {
    id("java")
    id("application")
}

group = "com.codefromjames"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")

    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("ch.qos.logback:logback-classic:1.5.13")
}

tasks.compileJava {
    options.compilerArgs.add("--enable-preview")
    options.compilerArgs.add("--add-modules")
    options.compilerArgs.add("jdk.incubator.vector")
}

tasks.compileTestJava {
    options.compilerArgs.add("--enable-preview")
    options.compilerArgs.add("--add-modules")
    options.compilerArgs.add("jdk.incubator.vector")
}

application {
    mainClass = "com.codefromjames.vector.HNSWExample"
    applicationDefaultJvmArgs = listOf("--enable-preview",
                                 "--add-modules", "jdk.incubator.vector")
}

tasks.test {
    useJUnitPlatform()
}
