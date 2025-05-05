plugins {
    id("java")
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
    implementation("org.nd4j:nd4j-native-platform:1.0.0-M2.1")
}

tasks.test {
    useJUnitPlatform()
}