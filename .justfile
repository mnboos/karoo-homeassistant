set dotenv-load := true
#set dotenv-required := true
set windows-shell := ["powershell.exe", "/c"]
set shell := ["bash", "-c"]

JAVA_HOME := "C:\\Program Files\\Java\\jdk-21.0.10"

devs:
    adb devices

install:
    adb -s 00446GA250970042 install -r app/build/outputs/apk/debug/app-debug.apk

make-spotless:
    ./gradlew :spotlessApply


build: make-spotless
    ./gradlew build

clean:
    ./gradlew clean

list-tasks:
    ./gradlew tasks

kill-java:
    taskkill /F /FI "IMAGENAME eq java.exe"