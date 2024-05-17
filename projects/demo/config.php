<?php
$host = 'localhost';
$dbuser = 'root';
$dbpassword = '';
$dbname = 'project';

/* Attempt to connect to MySQL database */
$link = mysqli_connect($host, $dbuser, $dbpassword, $dbname);
// 輸入中文也OK的編碼
mysqli_query($link, 'SET NAMES utf8');

// Check connection
if($link === false){
    die("ERROR: Could not connect. " . mysqli_connect_error());
}
else{
    return $link;
}
?>