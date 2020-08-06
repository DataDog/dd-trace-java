ROOT_DIR:=$(shell dirname $(realpath $(firstword $(MAKEFILE_LIST))))
DOCKER_COMPOSE_FILE:=$(addprefix ${ROOT_DIR}/, docker-compose.yaml )
BUILD_DIR:=$(ROOT_DIR)/build
RESULTS_DIR:=$(ROOT_DIR)/results

TARGETS=$(subst ${ROOT_DIR}/,,$(patsubst %.sh, %, $(wildcard ${ROOT_DIR}/*.sh)))
.PHONY: list ${TARGETS}

list:
	@echo ${TARGETS}

${TARGETS}: % : ${RESULTS_DIR}/%

${BUILD_DIR}/%: 
	@mkdir -p "$@-tmp"
	@bash -c "source ${ROOT_DIR}/$*.sh; TARGET=$@-tmp init"
	@mv "$@-tmp" "$@"

${RESULTS_DIR}/%: ${BUILD_DIR}/%
	@mkdir -p "$@-tmp"
	@echo bash -c "source ${ROOT_DIR}/$*.sh; TARGET=${BUILD_DIR}/$* tests"
	@bash -c "source ${ROOT_DIR}/$*.sh; TARGET=${BUILD_DIR}/$* RESULTS=$@-tmp collect"
	@mv "$@-tmp" "$@"

all: ${TARGETS}
