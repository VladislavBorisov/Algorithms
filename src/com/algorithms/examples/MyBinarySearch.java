package com.algorithms.examples;

public class MyBinarySearch {

	public int binarySearch(int[] inputArr, int key) {

		int start = 0;
		int end = inputArr.length - 1;
		while (start <= end) {
			int mid = (start + end) / 2;
			if (key == inputArr[mid]) {
				return mid;
			}
			if (key < inputArr[mid]) {
				end = mid - 1;
			} else {
				start = mid + 1;
			}
		}
		return -1;
	}

	public static void main(String[] args) {

		MyBinarySearch mbs = new MyBinarySearch();
		
		int[] arr = { 1, 2, 3, 4, 5, 6, 7, 8 };
		System.out.println("Key 4's position: " + mbs.binarySearch(arr, 4));
		
		int[] arr1 = { 6, 34, 78, 123, 432, 900 };
		System.out.println("Key 432's position: " + mbs.binarySearch(arr1, 432));
	}
}
