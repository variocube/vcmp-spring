#!/usr/bin/env bash

die() {
	echo >&2 "$@"
	exit 1
}

# Allow to make release only from master
[[ $(git rev-parse --abbrev-ref HEAD) == "master" ]] || die "Error: Release can only be made from master branch."

# Make sure we are up to date
echo -n "git pull... "
git pull

# Make sure there no local changes
[[ $(git status --porcelain) ]] && die "Error: Local changes detected."

# Read version from package.json
version=$(./gradlew -b build.gradle properties --no-daemon --console=plain -q | grep "^version:" | awk '{printf $2}')

# Create and push version tag
git tag "$version"
git push --tags
