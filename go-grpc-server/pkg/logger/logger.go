package logger

import (
	"log"
	"os"
)

// Check if output is a terminal (TTY)
func isTerminal(file *os.File) bool {
	fileInfo, err := file.Stat()
	if err != nil {
		return false
	}
	return (fileInfo.Mode() & os.ModeCharDevice) != 0
}

const (
	Reset = "\033[0m"  // Reset to default color
	Red   = "\033[31m" // Red for errors
	Blue  = "\033[34m" // Blue for informational messages
)

type Logger struct {
	errorLogger *log.Logger
	infoLogger  *log.Logger
	useColors   bool
}

func NewLogger() *Logger {
	// Check if REPORT_PATH is set (writing to file in this case)
	// Also check if output is actually a terminal
	reportPath := os.Getenv("REPORT_PATH")
	useColors := false
	
	if reportPath == "" {
		// Not writing to report directory - check if output is terminal
		useColors = isTerminal(os.Stderr) && isTerminal(os.Stdout)
	} else {
		// REPORT_PATH is set - definitely writing to file, disable colors
		useColors = false
	}
	
	var errorPrefix, infoPrefix string
	if useColors {
		errorPrefix = Red + "ERROR: " + Reset
		infoPrefix = Blue + "INFO: " + Reset
	} else {
		errorPrefix = "ERROR: "
		infoPrefix = "INFO: "
	}
	
	return &Logger{
		errorLogger: log.New(os.Stderr, errorPrefix, log.Ldate|log.Ltime|log.Lshortfile),
		infoLogger:  log.New(os.Stdout, infoPrefix, log.Ldate|log.Ltime),
		useColors:   useColors,
	}
}

func (l *Logger) Error(message string, err error) {
	if err != nil {
		l.errorLogger.Printf("%s: %v", message, err)
	} else {
		l.errorLogger.Printf("%s", message)
	}
}

func (l *Logger) Info(message string) {
	l.infoLogger.Println(message)
}

func (l *Logger) WithError(err error) string {
	if err != nil {
		return "Error: " + err.Error()
	}
	return ""
}
