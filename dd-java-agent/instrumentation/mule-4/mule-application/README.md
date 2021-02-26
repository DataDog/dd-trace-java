# Working with the Mule Application

This directory contains a Mule application. It is built using maven, and unless
you know your Mule xml by heart the _easiest_ way is to download
[Anypoint Studio 7](https://www.mulesoft.com/lp/dl/studio).

## Configure Anypoint Studio

This Mule Application is currently built using version `4.2.2.` of Mule, so to avoid
problems, it is best to install the `4.2.2` runtime into Anypoint Studio.

Follow the instructions on how to [install a different mule runtime](https://mulesy.com/install-different-mule-runtime-version-in-studio/),
and select the `4.2.2 EE` version.

> **_Note:_** As of version 4 of Mule, there is no longer an update site that provides
> community versions of the Mule runtime, so the Enterprise Edition needs to be installed,
> even though the application can't use any of them.

## Importing the project into Anypoint Studio

* Start Anypoint Studio and create a _workspace_ somewhere unrelated to this directory.
* From the menu, select: **File** -> **Import...**.
* In the import dialog select: **Anypoint Studio** -> **Anypoint Studio project from File System**.
* Uncheck the **Copy project into workspace** checkbox.
* Select this directory as **Project Root**.
* Change **Server Runtime** to **Mule Server 4.2.2 EE**.
* Make sure that you have **unchecked** the **Copy project into workspace** checkbox.
* Press **Finish**.
