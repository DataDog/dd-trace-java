# SSI injection metadata

## Adding a new deny metadata.

1. Adding or updating denied Java process metadata in order to avoid enabling the tracer is done by editing 
   the following files :

    * `base-requirements.json`
    * `denied-arguments.tsv`
    * `denied-environment-variables.tsv`

2. Then run the following command to build/update the `requirements.json` file:
    
    ```bash
    ./build-requirements.sh
    ```
