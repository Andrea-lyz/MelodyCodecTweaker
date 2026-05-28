plugins {
    id("com.android.application") version "8.5.2" apply false
}

// Bootstraps the Gradle wrapper on systems that don't yet have gradlew/gradlew.bat.
// Run `gradle wrapper` once after cloning to materialise gradle/wrapper/gradle-wrapper.jar
// and the gradlew launchers; afterwards, every command can use ./gradlew.
tasks.register<Wrapper>("wrapper") {
    gradleVersion = "8.10.2"
    distributionType = Wrapper.DistributionType.BIN
}
