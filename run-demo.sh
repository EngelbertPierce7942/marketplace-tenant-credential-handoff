#!/usr/bin/env sh
set -eu
mkdir -p out
javac -d out src/main/java/TenantCredentialHandoff.java
java -cp out TenantCredentialHandoff
