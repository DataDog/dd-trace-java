FROM python:3-alpine

RUN pip install awscli
RUN pip install boto3
