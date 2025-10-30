# Project Documentation for Image Autoclicker

## Overview
The Image Autoclicker is a Java application that automatically searches the entire screen for a specific image every 3 seconds and clicks a designated part of that image when it is found. This project utilizes various classes to manage screen capturing, image matching, and mouse control.

## Project Structure
```
image-autoclicker
├── src
│   ├── main
│   │   └── java
│   │       ├── AutoClicker.java
│   │       ├── ScreenScanner.java
│   │       ├── ImageMatcher.java
│   │       └── MouseController.java
│   └── test
│       └── java
│           └── AutoClickerTest.java
├── pom.xml
└── README.md
```

## Setup Instructions
1. **Clone the Repository**
   ```bash
   git clone <repository-url>
   cd image-autoclicker
   ```

2. **Build the Project**
   Ensure you have Maven installed, then run:
   ```bash
   mvn clean install
   ```

3. **Run the Application**
   Execute the main class:
   ```bash
   mvn exec:java -Dexec.mainClass="AutoClicker"
   ```

## Usage Guidelines
- The application will start scanning the screen every 3 seconds.
- Ensure the target image is available for matching.
- Adjust the scanning interval and target image path in the `AutoClicker.java` file as needed.

## Contributing
Contributions are welcome! Please submit a pull request or open an issue for any enhancements or bug fixes.

## License
This project is licensed under the MIT License. See the LICENSE file for details.