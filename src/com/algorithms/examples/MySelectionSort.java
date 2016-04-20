package com.algorithms.examples;

public class MySelectionSort {

	public static int[] doSelectionSort(int[] arr) {

		for (int i = 0; i < arr.length - 1; i++) {
			int index = i;
			for (int j = i + 1; j < arr.length; j++)
				if (arr[j] < arr[index])
					index = j;

			int smallerNumber = arr[index];
			arr[index] = arr[i];
			arr[i] = smallerNumber;
		}
		return arr;
	}

	public static void main(String a[]) {

		int[] arr1 = { 3, 34, 2, 56, 7, 67, 88, 42, 54, 98, 5667, 99, 135, 653 };
		int[] arr2 = doSelectionSort(arr1);
		for (int i : arr2) {
			System.out.print(i);
			System.out.print(", ");
		}
		System.out.print("\n" + arr2[9] + " -- is the 10 biggest element");
	}
}
