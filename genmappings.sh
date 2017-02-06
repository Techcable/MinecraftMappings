#!/usr/bin/env bash

# A thin wrapper around genmappings.kts which downloads the needed dependencies

if ! which java >/dev/null 2>&1; then
    echo "Java is not installed"
    exit 1;
fi;

if ! which kotlinc >/dev/null 2>&1; then
    echo "Kotlin compiler is not installed!"
    exit 1
fi;

if ! which wget >/dev/null 2>&1; then
    echo "wget is not installed"
    exit 1
fi;

if ! which sha256sum >/dev/null 2>&1; then
    echo "sha256sum not installed!"
    exit 1
fi;

dependencies=("SrgLib.jar" "opencsv.jar" "gson.jar" "guava.jar")
dependency_urls=(
    "https://repo.techcable.net/content/groups/public/net/techcable/srglib/0.1.2/srglib-0.1.2.jar"
    "https://repo.maven.apache.org/maven2/com/opencsv/opencsv/3.9/opencsv-3.9.jar"
    "https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.8.0/gson-2.8.0.jar"
    "https://repo.maven.apache.org/maven2/com/google/guava/guava/21.0/guava-21.0.jar"
)

mkdir -p lib
for ((i=0; i<${#dependencies[@]}; i++)); do
    dependency=${dependencies[i]}
    url=${dependency_urls[i]}
    if [ ! -f "lib/$dependency" ]; then
        echo "Downloading $dependency to lib/$dependency";
        wget "$url" -O "lib/$dependency" >/dev/null 2>&1 || (echo "Unable to download $dependency" & exit 1)
    fi;
done;

classpath=""
for dependency in "${dependencies[@]}"; do
    classpath="$classpath:lib/$dependency";
done
classpath=${classpath:1} # Strip leading ':'

expected_hash=$(sha256sum genmappings.kt | sed -r 's/(\w+).*/\1/')

if [ ! -f "bin/genmappings.jar" ] || [ "$expected_hash" != "$(cat bin/genmappings.kt.sha256)" ]; then
    echo "Recompiling genmappings.kt"
    mkdir -p bin
    kotlinc -cp "$classpath" -d bin/genmappings.jar genmappings.kt || exit 1;
    echo "$expected_hash" > "bin/genmappings.kt.sha256"
fi;
kotlin -cp "$classpath:bin/genmappings.jar" GenmappingsKt "$@"
exit $?
