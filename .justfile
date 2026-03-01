set dotenv-load := true
#set dotenv-required := true
set windows-shell := ["powershell.exe", "/c"]
set shell := ["bash", "-c"]

JAVA_HOME := "C:\\Program Files\\Java\\jdk-21.0.10"


install:
    adb install -r app/build/

make-spotless:
    ./gradlew :spotlessApply


build: make-spotless
    ./gradlew build