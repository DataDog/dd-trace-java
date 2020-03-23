import os
import boto3

for path, subdirs, files in os.walk('./workspace/dd-java-agent/build/libs'):
    for name in files:
      if 'dd-java-agent-' in name:
        print(os.path.join(path, name))
