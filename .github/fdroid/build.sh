#!/bin/bash
# Builds the fdroid flavor the way F-Droid's build server does, so the APK we
# sign and publish matches F-Droid's own build (reproducible builds). Runs in
# registry.gitlab.com/fdroid/fdroidserver:buildserver-trixie, following
# fdroiddata's CI build job, with this directory mounted at /recipe and the
# output directory at /out.
#
# Usage: build.sh <commit> <versionName> <versionCode>
# Writes /out/dev.notune.transcribe_<versionCode>.apk (unsigned).
set -euxo pipefail
commit=$1
version_name=$2
version_code=$3
appid=dev.notune.transcribe
# fdroidserver revision used for the build (any recent one gives the same APK).
fdroidserver_commit=5806784d0d43bed7b938fd4b327162da68927060

source /etc/profile.d/bsenv.sh
export ANDROID_HOME=/opt/android-sdk
apt-get update -qq
sdkmanager "platform-tools" "build-tools;31.0.0" > /dev/null
git clone -q https://gitlab.com/fdroid/fdroidserver.git "$fdroidserver"
git -C "$fdroidserver" checkout -q "$fdroidserver_commit"
git -C "$home_vagrant/gradlew-fdroid" pull -q || true
apt-get install -y -qq sudo openjdk-21-jdk-headless > /dev/null
update-alternatives --set java /usr/lib/jvm/java-21-openjdk-amd64/bin/java

mkdir -p "$home_vagrant/metadata" "$home_vagrant/srclibs" "$home_vagrant/build" "$home_vagrant/.android" "$home_vagrant/.gradle"
sed -e "s/@COMMIT@/$commit/" -e "s/@VERSION_NAME@/$version_name/" -e "s/@VERSION_CODE@/$version_code/" \
    /recipe/$appid.yml > "$home_vagrant/metadata/$appid.yml"
chown -R vagrant "$home_vagrant"

cd "$home_vagrant"
fdroid() {
    sudo --preserve-env --user vagrant \
        env PATH="$fdroidserver:$PATH" PYTHONPATH="$fdroidserver:$fdroidserver/examples" \
        PYTHONUNBUFFERED=true HOME="$home_vagrant" GRADLE_USER_HOME="$home_vagrant/.gradle" \
        fdroid "$@"
}
# Clones the source; the build takes SOURCE_DATE_EPOCH from it.
fdroid fetchsrclibs "$appid:$version_code" --verbose
fdroid build --verbose --test --refresh-scanner --on-server --no-tarball "$appid:$version_code"
cp "tmp/${appid}_$version_code.apk" /out/
