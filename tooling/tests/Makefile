ROOT_DIR:=$(shell dirname $(realpath $(firstword $(MAKEFILE_LIST))))
DOCKER_COMPOSE_FILE:=$(addprefix ${ROOT_DIR}/, docker-compose.yaml )

TARGETS=$(shell docker-compose -f ${DOCKER_COMPOSE_FILE} config --services)

.PHONY: ${TARGETS} list

list:
	@echo All targets:
	@echo "  "${TARGETS}

${TARGETS}: export COMPOSE_DOCKER_CLI_BUILD=1 
${TARGETS}: export DOCKER_BUILDKIT=1
${TARGETS}:
	@docker-compose -f ${DOCKER_COMPOSE_FILE} build --force-rm $@ 
	@docker-compose -f ${DOCKER_COMPOSE_FILE} run --rm $@
	
