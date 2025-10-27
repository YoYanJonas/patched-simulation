package logger

import (
	"log"
	"os"
)

const (
	Reset = "\033[0m"  // Reset to default color
	Red   = "\033[31m" // Red for errors
	Blue  = "\033[34m" // Blue for informational messages
)

type Logger struct {
	errorLogger *log.Logger
	infoLogger  *log.Logger
}

func NewLogger() *Logger {
	return &Logger{
		errorLogger: log.New(os.Stderr, Red+"ERROR: "+Reset, log.Ldate|log.Ltime|log.Lshortfile),
		infoLogger:  log.New(os.Stdout, Blue+"INFO: "+Reset, log.Ldate|log.Ltime),
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
