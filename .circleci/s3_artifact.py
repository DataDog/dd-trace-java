import os
import boto3

client = boto3.client('s3', aws_access_key_id=os.getenv('AWS_ACCESS_KEY_ID'),aws_secret_access_key=os.getenv('AWS_SECRET_ACCESS_KEY'))
transfer = boto3.s3.transfer.S3Transfer(client)

for path, subdirs, files in os.walk('./workspace/dd-java-agent/build/libs'):
    for name in files:
      if 'dd-java-agent-' in name:
        transfer.upload_file(os.path.join(path, name), 'datadog-reliability-env', f'java/{name}')
