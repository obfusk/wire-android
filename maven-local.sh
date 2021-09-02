#!/bin/bash
# FIXME: replace with properly published artefacts!
set -xe
wget -O artefacts.zip -- https://github.com/obfusk/wire-android/releases/download/foss-wip-artefacts/artefacts.zip
printf 'e8e64f3495c874a0df338a7b85b2e683b90d3656ab153eb5bb6169146bb433553d3dcac2e7a0d6d71ba2d94eab4e90ad5cd021578ea3740277757d2d71e93af3  artefacts.zip\n' | sha512sum -c
unzip artefacts.zip
cd artefacts
for artefact in *.aar *.jar; do
  pom="${artefact%.*}.pom"
  mvn install:install-file -Dfile="$artefact" -DpomFile="$pom"
done
cd ..
rm -fr artefacts.zip artefacts/
