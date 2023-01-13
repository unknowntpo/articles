
all: help

## help: print this help message
help:
	@echo 'Usage:'
	@sed -n 's/^##//p' ${MAKEFILE_LIST} | column -t -s ':' |  sed -e 's/^/ /'

## new/post: create a new post, use POST to specify post name
new/post:
	cd quickstart && hugo new posts/$(POST).md

## serve/drafts: preview the page in hugo server
serve/drafts:
	hugo server --buildDrafts --source ./quickstart/

## publish: copy ./quickstart/public to ./docs to allow github display it
publish:
	hugo --source ./quickstart/
	cp -r ./quickstart/public/ ./docs

.PHONY: help new/post server/drafts publish