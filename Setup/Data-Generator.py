#!/usr/bin/python
import sys
import requests
import time
import ast

args = str(sys.argv)
urls = ["http://localhost:7896/iot/d?k=test&i=IoT-R1", "http://localhost:7896/iot/d?k=test&i=IoT-R2",
        "http://localhost:7896/iot/d?k=test&i=IoT-R3", "http://localhost:7896/iot/d?k=test&i=IoT-R4",
        "http://localhost:7896/iot/d?k=test&i=IoT-R5"]
if args == None:
    print(
        "Please enter the strategy. For simulating fire enter 'fire', for a failure enter 'failure', for a test case enter 'test', and for a normal case enter 'normal'")
    possibleStrategies = ["fire", "normal", "failure", "test"]
    inputStrategy = input("Please enter the strategy.\n")
    if inputStrategy in possibleStrategies:
        correct = True
        strategy = inputStrategy
    else:
        correct = False
    while correct == False:
        inputStrategy = input(
            str(inputStrategy) + " is not a valid strategy. Please enter: 'fire', 'failure', 'normal' or 'test'.\n")
        if inputStrategy in possibleStrategies:
            correct = True
            strategy = inputStrategy
        else:
            correct = False
    print("Do you want to send the requests to local or the server?")

    anotherURL = input("For server enter 'y' for local enter 'n'\n")
    if anotherURL == "y":
        urls = ["http://10.25.2.146:7896/iot/d?k=test&i=IoT-R1", "http://10.25.2.146:7896/iot/d?k=test&i=IoT-R2",
                "http://10.25.2.146:7896/iot/d?k=test&i=IoT-R3", "http://10.25.2.146:7896/iot/d?k=test&i=IoT-R4",
                "http://10.25.2.146:7896/iot/d?k=test&i=IoT-R5"]

else:
    strategy = ast.literal_eval(args)[2]

def generateData(urlList, strategyType):
    if strategyType == "normal":
        payload = "t|20"
        cond = True
        while cond:
            for url in urlList:
                makeRequest(url, payload)

    elif strategyType == "fire":
        tmp = 20
        for tmp in range(20, 50):
            payload = "t|" + str(tmp)
            for url in urlList:
                makeRequest(url, payload)
        payload = "t|50"
        cond = True
        while cond:
            for url in urlList:
                makeRequest(url, payload)

    elif strategyType == "failure":
        for tmp in range(20, 100):
            payload = "t|" + str(tmp)
            for url in urlList:
                makeRequest(url, payload)
        payload = "t|100"
        cond = True
        while cond:
            for url in urls:
                makeRequest(url, payload)

    elif strategyType == "test":
        for tmp in range(20, 25):
            payload = "t|" + str(tmp)
            for url in urlList:
                makeRequest(url, payload)

    else:
        print("Something went wrong! Your input: " + strategy)


def makeRequest(url, payload):
    headers = {'Content-Type': 'application/json',
               'fiware-service': 'cepware',
               'fiware-servicepath': '/rooms'}
    try:
        r = requests.post(url, headers=headers, data=payload)
        statusCode = r.status_code
        r.raise_for_status()
        print("Sent " + payload + "to URL: " + url + ". Statuscode: " + str(statusCode))
        time.sleep(.300)
    except (ConnectionError, ConnectionRefusedError, requests.exceptions.HTTPError):
        if (statusCode > 399):
            print("Connection refused. Statuscode is: " + str(
                statusCode) + ". Bad request! Check if infrastructure is set up.")
        else:
            print(
                "Something went wrong while sending the request to URL: " + url + " ;with payload: " + payload + ". Statuscode: " + str(
                    statusCode))
        time.sleep(.300)


generateData(urls, strategy)
