#!/bin/bash -xe

TARGET=$1
SOURCE=$2
TAR_STRIP_COMPONENTS=1

if [[ ! -e $TARGET ]]; then
    mkdir $TARGET
elif [[ ! -d $TARGET ]]; then
    echo "$TARGET already exists but is not a directory" 1>&2
    exit 1
fi

cd $TARGET
curl -o source.tar -L ${SOURCE}
tar xf source.tar --strip-components=${TAR_STRIP_COMPONENTS}
rm -rf source.tar
