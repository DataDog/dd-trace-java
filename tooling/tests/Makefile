ROOT_DIR:=$(shell dirname $(realpath $(firstword $(MAKEFILE_LIST))))
DOCKER_COMPOSE_FILE:=$(addprefix ${ROOT_DIR}/, docker-compose.yaml )
BUILD_DIR:=$(ROOT_DIR)/build

TARGETS=$(subst ${ROOT_DIR}/,,$(patsubst %.sh, %, $(wildcard ${ROOT_DIR}/*.sh)))
.PHONY: list ${TARGETS}

INIT_TARGETS=$(addprefix ${BUILD_DIR}/, ${TARGETS})
list:
	@echo ${TARGETS}

${TARGETS}: ${INIT_TARGETS}

${INIT_TARGETS}: 
	@bash -c "source $(subst ${BUILD_DIR},${ROOT_DIR},$@).sh; TARGET=$@ init"

all: ${TARGETS}
