To generate the Protobuf message, we need to use an old version of protobuf (3.0.0), because we want to support the protobuf library starting at 3.0.0

Instructions to do it:
```
cd ~/Downloads
wget https://github.com/protocolbuffers/protobuf/releases/download/v3.0.0/protoc-3.0.0-osx-x86_64.zip
unzip protoc-3.0.0-osx-x86_64.zip -d protoc-3.0.0
cd protoc-3.0.0/bin
chmod +x protoc
# Open System Preferences, Go to Security & Privacy, In the General tab, you should see a message about the blocked app: "protoc" was blocked from use because it is not from an identified developer." Click Open Anyway.
# Then, inside this folder (with the protobuf files)
~/Downloads/protoc-3.0.0/bin/protoc --java_out=. message.proto
```
