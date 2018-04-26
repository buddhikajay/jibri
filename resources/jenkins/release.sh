#!/bin/bash
set -e

# This script should be run after the build/tests have successfully completed

MAJOR_MINOR=$1

# Prune any local tags that aren't on the remote
git fetch --prune origin "+refs/tags/*:refs/tags/*"

DESCRIBE=`git describe --tags --always`
REV=$(git log --pretty=format:'%h' -n 1)

CURR_VERSION=`echo $DESCRIBE | awk '{split($0,a,"-"); print a[1]}'`
echo "Got current git version $CURR_VERSION"
MAJOR_VERSION=`echo $CURR_VERSION | awk '{split($0,a,"."); print a[1]}'`
MINOR_VERSION=`echo $CURR_VERSION | awk '{split($0,a,"."); print a[2]}'`

if [ "$MAJOR_MINOR" == "Minor" ]; then
    MINOR_VERSION=$((MINOR_VERSION+1))
elif [ "$MAJOR_MINOR" == "Major" ]; then
    MAJOR_VERSION=$((MAJOR_VERSION+1))
    MINOR_VERSION=0
else
    echo "Error, unrecognized release type $MAJOR_MINOR.  Options are 'Major' and 'Minor'"
    exit 1
fi

NEW_VERSION=$MAJOR_VERSION.$MINOR_VERSION
echo "New version will be $NEW_VERSION"

cd $WORKSPACE

git tag -a $NEW_VERSION -m "New $MAJOR_MINOR release"

dch -v "$NEW_VERSION.$BUILD_NUMBER-1" "Built from git. $REV"
dch -D unstable -r ""

dpkg-buildpackage -A -rfakeroot -us -uc

ARTIFACTS_DIR=$WORKSPACE/$BUILD_NUMBER-ARTIFACTS
mkdir $ARTIFACTS_DIR
mv $WORKSPACE/../jibri_$NEW_VERSION* $ARTIFACTS_DIR

git push origin $NEW_VERSION
