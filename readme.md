# Basic banking service


## Table of contents
1. [Purpose](#purpose)
2. [Build & Run](#buildnrun)
3. [API](#api)
   1. [POST /api/v1/balance](#api.balance)
   1. [POST /api/v1/transfer](#api.transfer)
   1. [POST /api/v1/create](#api.create)
   1. [POST /api/v1/admin/init](#api.init)
4. [Stress Client](#stressclient)


## Purpose <a name="purpose"></a>

_(this is a interview task)_

Provides simple REST API for basic banking operations:
- checking balance
- transferring from one account to another
- creating new accounts

server app is not supposed to run parallel instances in front of single DB

see PreProdOverview.txt for functional and stress testing, throughput, performance on further improvements   

## Build & Run <a name="buildnrun"></a>

You will require java8+, gradle6+, MYSQL 5.8

```
gradle clean fatJar
```
That will build an artifact ./build/libs/SimpleBank-all-x.x.jar 

It's fully constructed fatJar with all libraries, you may simply run in with command: 
```
java -jar SimpleBank-all-x.x.jar 
```


## API <a name="api"></a>

### POST /api/v1/balance <a name="api.balance"></a>
***Checks balance for $xxx account***

HEADER: Content-Type: application/json  
BODY:
```
{
  "accId": $xxx
}
```
$xxx -- Integer

returns 200 response code with balance in body, if account exists  
returns 400 response code with ACC_NOT_EXISTS description in body, if account doesn't exists  
returns 503 response code with OVERLOADED description in body, if service is overloaded right now  
returns 500 response code without body in case of other errors


### POST /api/v1/transfer <a name="api.transfer"></a>
***Transfers $zzz amount from $xxx account to $yyy***

HEADER: Content-Type: application/json  
BODY:
```
{
  "senderId": $xxx,
  "recipientId": $yyy,
  "amount": $zzz
}
```
$xxx -- Integer  
$yyy -- Integer  
$zzz -- Long

returns 200 response code with new balances in body, if both account exist and sender has enough amount   
returns 400 response code with ACC_NOT_EXISTS description in body, if any account doesn't exist  
returns 400 response code with WRONG_AMOUNT description in body, sender doesn't have enough money    
returns 400 response code with WRONG_AMOUNT description in body, if recipient's account gonna exceed Long.MAX_VALUE      
returns 503 response code with OVERLOADED description in body, if service is overloaded right now  
returns 500 response code without body in case of other errors  


### POST /api/v1/create <a name="api.create"></a>
***Create account with $xxx id and $zzz amount***

HEADER: Content-Type: application/json  
BODY:
```
{
  "accId": $xxx,
  "amount": $zzz
}
```
$xxx -- Integer  
$zzz -- Long

returns 200 response code with SUCCESS description, if account was created   
returns 400 response code with WRONG_AMOUNT description in body, if amount is not positive       
returns 400 response code with ACC_ALREADY_EXISTS description in body, if account already exists       
returns 503 response code with OVERLOADED description in body, if service is overloaded right now  
returns 500 response code without body in case of other errors  


### POST /api/v1/admin/init <a name="api.init"></a>
***Deletes all accounts and creates new ones with id from 1 to $xxx (inclusive) with $zzz amount***

HEADER: Content-Type: application/json  
BODY:
```
{
  "dbSize": $xxx,
  "initAmount": $zzz
}
```
$xxx -- Integer  
$zzz -- Long

returns 200 response code with "OK" in body, if succeed   
returns 500 response code without body in other cases


## Stress Client <a name="stressclient"></a>

Bombs given server with meaningful load.

Entry point:
me/stasmarkin/simplebank/entrypoint/StressClient.kt

Configuration: src/main/resources/configs/client/stress.json
```
{
  "address": "localhost",
  "port": 8080,
  "concurrency": 160,
  "dbSize": 10000,

  "init" : {
    "reinit": true,
    "initAmount": 100000
  },

  "scenarios": {
    "balance": 0,
    "balanceMissed": 0,
    "transferRnd2rnd": 0,
    "transferRnd2one": 1,
    "transferRnd2none": 0,
    "create": 0,
    "createDuplicate": 0
  }
}
```
**address** and port -- defines server's address to bomb      
**concurrency** -- number of simultaneous requests    
**dbSize** -- expected number of accounts in db (affects scenarios)    
**init.reinit** -- if true, then client will reinit db with $dbSize accounts with $init.initAmount amount on each of them  
**scenarios** -- block with scenarios. Each scenario has weight, so all requests will have same weighted distribution  
**scenarios.balance** -- valid balance request  
**scenarios.balanceMissed** -- balance request for not existing id  
**scenarios.transferRnd2rnd** -- transfer request from random to random account  
**scenarios.transferRnd2one** -- transfer request from random account to account with id1  
**scenarios.transferRnd2none** -- transfer request from random account to not existing account  
**scenarios.create** -- valid create request (with not existing id)  
**scenarios.createDuplicate** -- valid create request with already existing id  
