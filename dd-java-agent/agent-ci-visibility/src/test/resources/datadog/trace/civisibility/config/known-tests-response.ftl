{
    "data": {
        "id": "9p1jTQLXB8g",
        "type": "ci_app_libraries_tests",
        "attributes": {
            "tests": {
                "test-bundle-a": {
                    "test-suite-a": [
                        "test-name-1",
                        "test-name-2"
                    ],
                    "test-suite-b": [
                        "another-test-name-1",
                        "test-name-2"
                    ]
                },
                "test-bundle-N": {
                    "test-suite-M": [
                        "test-name-1",
                        "test-name-2"
                    ]
                }
            }<#if pageInfo??>,
            "page_info": {
                "size": ${pageInfo.size},
                "has_next": ${pageInfo.hasNext?c}<#if pageInfo.cursor??>,
                "cursor": "${pageInfo.cursor}"</#if>
            }</#if>
        }
    }
}
