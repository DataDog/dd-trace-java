ROOT_DIR:=$(shell dirname $(realpath $(firstword $(MAKEFILE_LIST))))
DOCKER_COMPOSE_FILE:=$(addprefix ${ROOT_DIR}/, docker-compose.yaml )

TARGETS=$(shell docker-compose -f ${DOCKER_COMPOSE_FILE} config --services)

${TARGETS}: COMPOSE_DOCKER_CLI_BUILD=1 
${TARGETS}: DOCKER_BUILDKIT=1
${TARGETS}:
	@docker-compose -f ${DOCKER_COMPOSE_FILE} run --rm $@ bash 


all:
	@echo ${TARGETS}
  
